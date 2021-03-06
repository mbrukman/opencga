/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.catalog.db.mongodb;

import com.mongodb.DuplicateKeyException;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.apache.commons.lang3.NotImplementedException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.datastore.mongodb.MongoDBCollection;
import org.opencb.opencga.catalog.db.api.DBIterator;
import org.opencb.opencga.catalog.db.api.ProjectDBAdaptor;
import org.opencb.opencga.catalog.db.api.UserDBAdaptor;
import org.opencb.opencga.catalog.db.mongodb.converters.UserConverter;
import org.opencb.opencga.catalog.db.mongodb.iterators.MongoDBIterator;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.models.Project;
import org.opencb.opencga.core.models.Status;
import org.opencb.opencga.core.models.User;
import org.opencb.opencga.core.models.acls.permissions.StudyAclEntry;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

import static org.opencb.opencga.catalog.db.api.UserDBAdaptor.QueryParams.*;
import static org.opencb.opencga.catalog.db.mongodb.MongoDBUtils.*;

/**
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class UserMongoDBAdaptor extends MongoDBAdaptor implements UserDBAdaptor {

    private final MongoDBCollection userCollection;
    private UserConverter userConverter;

    public UserMongoDBAdaptor(MongoDBCollection userCollection, MongoDBAdaptorFactory dbAdaptorFactory) {
        super(LoggerFactory.getLogger(UserMongoDBAdaptor.class));
        this.dbAdaptorFactory = dbAdaptorFactory;
        this.userCollection = userCollection;
        this.userConverter = new UserConverter();
    }

    /*
     * *************************
     * User methods
     * ***************************
     */

    @Override
    public QueryResult<User> insert(User user, QueryOptions options) throws CatalogDBException {
        checkParameter(user, "user");
        long startTime = startQuery();

        if (exists(user.getId())) {
            throw new CatalogDBException("User {id:\"" + user.getId() + "\"} already exists");
        }

        List<Project> projects = user.getProjects();
        user.setProjects(Collections.<Project>emptyList());

        user.setLastModified(TimeUtils.getTimeMillis());
        Document userDocument = userConverter.convertToStorageType(user);
        userDocument.append(PRIVATE_ID, user.getId());

        QueryResult insert;
        try {
            insert = userCollection.insert(userDocument, null);
        } catch (DuplicateKeyException e) {
            throw new CatalogDBException("User {id:\"" + user.getId() + "\"} already exists");
        }

        // Although using the converters we could be inserting the user directly, we could be inserting more than users and projects if
        // the projects given contains the studies, files, samples... all in the same collection. For this reason, we make different calls
        // to the createProject method that is able to control these things and will create the studies, files... in their corresponding
        // collections.
        String errorMsg = insert.getErrorMsg() != null ? insert.getErrorMsg() : "";
        for (Project p : projects) {
            String projectErrorMsg = dbAdaptorFactory.getCatalogProjectDbAdaptor().insert(p, user.getId(), options).getErrorMsg();
            if (projectErrorMsg != null && !projectErrorMsg.isEmpty()) {
                errorMsg += ", " + p.getId() + ":" + projectErrorMsg;
            }
        }

        //Get the inserted user.
        user.setProjects(projects);
        List<User> result = get(user.getId(), options, "null").getResult();

        return endQuery("insertUser", startTime, result, errorMsg, null);
    }

    @Override
    public QueryResult<User> get(String userId, QueryOptions options, String lastModified) throws CatalogDBException {

        checkId(userId);
        Query query = new Query(QueryParams.ID.key(), userId).append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED);
        if (lastModified != null && !lastModified.isEmpty()) {
            query.append(QueryParams.LAST_MODIFIED.key(), "!=" + lastModified);
        }
        return get(query, options);
    }

    @Override
    public QueryResult changePassword(String userId, String oldPassword, String newPassword) throws CatalogDBException {
        long startTime = startQuery();

        Query query = new Query(QueryParams.ID.key(), userId);
        query.append(QueryParams.PASSWORD.key(), oldPassword);
        Bson bson = parseQuery(query);

        Bson set = Updates.set("password", newPassword);

        QueryResult<UpdateResult> update = userCollection.update(bson, set, null);
        if (update.getResult().get(0).getModifiedCount() == 0) {  //0 query matches.
            throw new CatalogDBException("Bad user or password");
        }
        return endQuery("Change Password", startTime, update);
    }

    @Override
    public void updateUserLastModified(String userId) throws CatalogDBException {
        update(userId, new ObjectMap("lastModified", TimeUtils.getTimeMillis()));
    }

    @Override
    public QueryResult resetPassword(String userId, String email, String newCryptPass) throws CatalogDBException {
        long startTime = startQuery();

        Query query = new Query(QueryParams.ID.key(), userId);
        query.append(QueryParams.EMAIL.key(), email);
        Bson bson = parseQuery(query);

        Bson set = Updates.set("password", new Document("password", newCryptPass));

        QueryResult<UpdateResult> update = userCollection.update(bson, set, null);
        if (update.getResult().get(0).getModifiedCount() == 0) {  //0 query matches.
            throw new CatalogDBException("Bad user or email");
        }
        return endQuery("Reset Password", startTime, Arrays.asList("Password successfully changed"));
    }

    @Override
    public QueryResult setConfig(String userId, String name, Map<String, Object> config) throws CatalogDBException {
        long startTime = startQuery();

        // Set the config
        Bson bsonQuery = Filters.eq(QueryParams.ID.key(), userId);
        Bson filterDocument = getMongoDBDocument(config, "Config");
        Bson update = Updates.set(QueryParams.CONFIGS.key() + "." + name, filterDocument);

        QueryResult<UpdateResult> queryResult = userCollection.update(bsonQuery, update, null);

        if (queryResult.first().getModifiedCount() == 0) {
            throw new CatalogDBException("Could not create " + name + " configuration ");
        }

        QueryResult<User> userQueryResult = get(userId, new QueryOptions(), "");
        if (userQueryResult.getNumResults() == 0) {
            throw new CatalogDBException("Internal error: Could not retrieve user " + userId + " information");
        }

        return endQuery("Set config", startTime, Arrays.asList(userQueryResult.first().getConfigs().get(name)));
    }

    @Override
    public QueryResult<Long> deleteConfig(String userId, String name) throws CatalogDBException {
        long startTime = startQuery();

        // Insert the config
        Bson bsonQuery = Filters.and(
                Filters.eq(QueryParams.ID.key(), userId),
                Filters.exists(QueryParams.CONFIGS.key() + "." + name)
        );
        Bson update = Updates.unset(QueryParams.CONFIGS.key() + "." + name);

        QueryResult<UpdateResult> queryResult = userCollection.update(bsonQuery, update, null);

        if (queryResult.first().getModifiedCount() == 0) {
            throw new CatalogDBException("Could not delete " + name + " configuration ");
        }

        QueryResult<User> userQueryResult = get(userId, new QueryOptions(), "");
        if (userQueryResult.getNumResults() == 0) {
            throw new CatalogDBException("Internal error: Could not retrieve user " + userId + " information");
        }

        return endQuery("Delete config", startTime, Arrays.asList(queryResult.first().getModifiedCount()));
    }

    @Override
    public QueryResult<User.Filter> addFilter(String userId, User.Filter filter) throws CatalogDBException {
        long startTime = startQuery();

        // Insert the filter
        Bson bsonQuery = Filters.and(
                Filters.eq(QueryParams.ID.key(), userId),
                Filters.ne(QueryParams.CONFIGS_FILTERS_NAME.key(), filter.getName())
        );
        Bson filterDocument = getMongoDBDocument(filter, "Filter");
        Bson update = Updates.push(QueryParams.CONFIGS_FILTERS.key(), filterDocument);

        QueryResult<UpdateResult> queryResult = userCollection.update(bsonQuery, update, null);

        if (queryResult.first().getModifiedCount() != 1) {
            if (queryResult.first().getModifiedCount() == 0) {
                throw new CatalogDBException("Internal error: The filter could not be stored.");
            } else {
                // This error should NEVER be raised.
                throw new CatalogDBException("User: There was a critical error when storing the filter. Is has been inserted "
                        + queryResult.first().getModifiedCount() + " times.");
            }
        }
        return endQuery("addFilter", startTime, Arrays.asList(filter));
    }

    @Override
    public QueryResult<Long> updateFilter(String userId, String name, ObjectMap params) throws CatalogDBException {
        long startTime = startQuery();

        if (params.isEmpty()) {
            throw new CatalogDBException("Nothing to be updated. No parameters were passed.");
        }

        final String prefixUpdate = CONFIGS_FILTERS.key() + ".$.";
        Document parameters = new Document();

        if (params.get(FilterParams.DESCRIPTION.key()) != null) {
            parameters.put(prefixUpdate + FilterParams.DESCRIPTION.key(), params.get(FilterParams.DESCRIPTION.key()));
        }

        if (params.get(FilterParams.BIOFORMAT.key()) != null) {
            parameters.put(prefixUpdate + FilterParams.BIOFORMAT.key(), params.get(FilterParams.BIOFORMAT.key()).toString());
        }

        if (params.get(FilterParams.QUERY.key()) != null) {
            parameters.put(prefixUpdate + FilterParams.QUERY.key(), getMongoDBDocument(params.get(FilterParams.QUERY.key()), "Query"));
        }

        if (params.get(FilterParams.OPTIONS.key()) != null) {
            parameters.put(prefixUpdate + FilterParams.OPTIONS.key(), getMongoDBDocument(params.get(FilterParams.OPTIONS.key()),
                    "Options"));
        }

        if (parameters.isEmpty()) {
            throw new CatalogDBException("Nothing to be updated. Parameters were not recognised.");
        }

        Query query = new Query()
                .append(ID.key(), userId)
                .append(CONFIGS_FILTERS_NAME.key(), name);
        QueryResult<UpdateResult> update = userCollection.update(parseQuery(query), new Document("$set", parameters), null);
        return endQuery("Update filter", startTime, Arrays.asList(update.first().getModifiedCount()));
    }

    @Override
    public QueryResult deleteFilter(String userId, String name) throws CatalogDBException {
        long startTime = startQuery();

        // Delete the filter
        Bson bsonQuery = Filters.and(
                Filters.eq(QueryParams.ID.key(), userId),
                Filters.eq(QueryParams.CONFIGS_FILTERS_NAME.key(), name)
        );
        Bson update = Updates.pull(QueryParams.CONFIGS_FILTERS.key(), new Document(FilterParams.NAME.key(), name));
        QueryResult<UpdateResult> queryResult = userCollection.update(bsonQuery, update, null);

        if (queryResult.first().getModifiedCount() == 0) {
            throw new CatalogDBException("Internal error: Filter " + name + " could not be removed");
        }

        return endQuery("Delete filter", startTime, Arrays.asList(queryResult.first().getModifiedCount()));
    }

    @Override
    public QueryResult<Long> count(Query query) throws CatalogDBException {
        Bson bsonDocument = parseQuery(query);
        return userCollection.count(bsonDocument);
    }

    @Override
    public QueryResult<Long> count(Query query, String user, StudyAclEntry.StudyPermissions studyPermission) throws CatalogDBException {
        throw new NotImplementedException("Count not implemented for users");
    }

    @Override
    public QueryResult distinct(Query query, String field) throws CatalogDBException {
        Bson bsonDocument = parseQuery(query);
        return userCollection.distinct(field, bsonDocument);
    }

    @Override
    public QueryResult stats(Query query) {
        return null;
    }

    @Override
    public QueryResult<User> get(Query query, QueryOptions options) throws CatalogDBException {
        if (!query.containsKey(QueryParams.STATUS_NAME.key())) {
            query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED);
        }
        Bson bson = parseQuery(query);
        QueryResult<User> userQueryResult = userCollection.find(bson, null, userConverter, options);

        for (User user : userQueryResult.getResult()) {
            if (user.getProjects() != null) {
                List<Project> projects = new ArrayList<>(user.getProjects().size());
                for (Project project : user.getProjects()) {
                    Query query1 = new Query(ProjectDBAdaptor.QueryParams.UID.key(), project.getUid());
                    QueryResult<Project> projectQueryResult = dbAdaptorFactory.getCatalogProjectDbAdaptor().get(query1, options);
                    projects.add(projectQueryResult.first());
                }
                user.setProjects(projects);
            }
        }
        return userQueryResult;
    }

    @Override
    public QueryResult<User> get(Query query, QueryOptions options, String user) throws CatalogDBException {
        throw new NotImplementedException("Get not implemented for user");
    }

    @Override
    public QueryResult nativeGet(Query query, QueryOptions options) throws CatalogDBException {
        if (!query.containsKey(QueryParams.STATUS_NAME.key())) {
            query.append(QueryParams.STATUS_NAME.key(), "!=" + Status.DELETED);
        }
        Bson bson = parseQuery(query);
        QueryResult<Document> queryResult = userCollection.find(bson, options);

        for (Document user : queryResult.getResult()) {
            ArrayList<Document> projects = (ArrayList<Document>) user.get("projects");
            if (projects.size() > 0) {
                List<Document> projectsTmp = new ArrayList<>(projects.size());
                for (Document project : projects) {
                    Query query1 = new Query(ProjectDBAdaptor.QueryParams.UID.key(), project.get(ProjectDBAdaptor
                            .QueryParams.UID.key()));
                    QueryResult<Document> queryResult1 = dbAdaptorFactory.getCatalogProjectDbAdaptor().nativeGet(query1, options);
                    projectsTmp.add(queryResult1.first());
                }
                user.remove("projects");
                user.append("projects", projectsTmp);
            }
        }

        return queryResult;
    }

    @Override
    public QueryResult<Long> update(Query query, ObjectMap parameters, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();
        Map<String, Object> userParameters = new HashMap<>();

        final String[] acceptedParams = {QueryParams.NAME.key(), QueryParams.EMAIL.key(), QueryParams.ORGANIZATION.key(),
                QueryParams.LAST_MODIFIED.key(), };
        filterStringParams(parameters, userParameters, acceptedParams);

        if (parameters.containsKey(QueryParams.STATUS_NAME.key())) {
            userParameters.put(QueryParams.STATUS_NAME.key(), parameters.get(QueryParams.STATUS_NAME.key()));
            userParameters.put(QueryParams.STATUS_DATE.key(), TimeUtils.getTime());
        }

        final String[] acceptedLongParams = {QueryParams.QUOTA.key(), QueryParams.SIZE.key()};
        filterLongParams(parameters, userParameters, acceptedLongParams);

        final String[] acceptedMapParams = {QueryParams.ATTRIBUTES.key()};
        filterMapParams(parameters, userParameters, acceptedMapParams);

        if (!userParameters.isEmpty()) {
            QueryResult<UpdateResult> update = userCollection.update(parseQuery(query),
                    new Document("$set", userParameters), null);
            return endQuery("Update user", startTime, Arrays.asList(update.getNumTotalResults()));
        }

        return endQuery("Update user", startTime, new QueryResult<>());
    }

    @Override
    public void delete(long id) throws CatalogDBException {
        Query query = new Query(QueryParams.ID.key(), id);
        delete(query);
    }

    @Override
    public void delete(Query query) throws CatalogDBException {
        QueryResult<DeleteResult> remove = userCollection.remove(parseQuery(query), null);

        if (remove.first().getDeletedCount() == 0) {
            throw CatalogDBException.deleteError("User");
        }
    }

    @Override
    public QueryResult<User> update(long id, ObjectMap parameters, QueryOptions queryOptions) throws CatalogDBException {
        throw new NotImplementedException("Update user by int id. The id should be a string.");
    }

    public QueryResult<User> update(String userId, ObjectMap parameters) throws CatalogDBException {
        long startTime = startQuery();
        checkId(userId);
        Query query = new Query(QueryParams.ID.key(), userId);
        QueryResult<Long> update = update(query, parameters, QueryOptions.empty());
        if (update.getResult().isEmpty() || update.first() != 1) {
            throw new CatalogDBException("Could not update user " + userId);
        }
        return endQuery("Update user", startTime, get(query, null));
    }

    QueryResult<Long> setStatus(Query query, String status) throws CatalogDBException {
        return update(query, new ObjectMap(QueryParams.STATUS_NAME.key(), status), QueryOptions.empty());
    }

    public QueryResult<User> setStatus(String userId, String status) throws CatalogDBException {
        return update(userId, new ObjectMap(QueryParams.STATUS_NAME.key(), status));
    }

    @Override
    public QueryResult<User> delete(long id, QueryOptions queryOptions) throws CatalogDBException {
        throw new CatalogDBException("Delete user by int id. The id should be a string.");
    }

    public QueryResult<User> delete(String id, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();

        checkId(id);
        // Check the user is active or banned
        Query query = new Query(QueryParams.ID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), User.UserStatus.READY + "," + User.UserStatus.BANNED);
        if (count(query).first() == 0) {
            query.put(QueryParams.STATUS_NAME.key(), User.UserStatus.DELETED);
            QueryOptions options = new QueryOptions(MongoDBCollection.INCLUDE, QueryParams.STATUS_NAME.key());
            User user = get(query, options).first();
            throw new CatalogDBException("The user {" + id + "} was already " + user.getStatus().getName());
        }

        // If we don't find the force parameter, we check first if the user does not have an active project.
        if (!queryOptions.containsKey(FORCE) || !queryOptions.getBoolean(FORCE)) {
            checkCanDelete(id);
        }

        if (queryOptions.containsKey(FORCE) && queryOptions.getBoolean(FORCE)) {
            // Delete the active projects (if any)
            query = new Query(ProjectDBAdaptor.QueryParams.USER_ID.key(), id);
            dbAdaptorFactory.getCatalogProjectDbAdaptor().delete(query, queryOptions);
        }

        // Change the status of the user to deleted
        setStatus(id, User.UserStatus.DELETED);

        query = new Query(QueryParams.ID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), User.UserStatus.DELETED);

        return endQuery("Delete user", startTime, get(query, queryOptions));
    }

    /**
     * Checks whether the userId has any active project.
     *
     * @param userId user id.
     * @throws CatalogDBException when the user has active projects. Projects must be deleted first.
     */
    private void checkCanDelete(String userId) throws CatalogDBException {
        checkId(userId);
        Query query = new Query(ProjectDBAdaptor.QueryParams.USER_ID.key(), userId)
                .append(ProjectDBAdaptor.QueryParams.STATUS_NAME.key(), Status.READY);
        Long count = dbAdaptorFactory.getCatalogProjectDbAdaptor().count(query).first();
        if (count > 0) {
            throw new CatalogDBException("The user {" + userId + "} cannot be deleted. The user has " + count + " projects in use.");
        }
    }

    @Override
    public QueryResult<Long> delete(Query query, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Remove not yet implemented.");

    }

    @Override
    public QueryResult<User> remove(long id, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Remove not yet implemented.");
    }

    @Override
    public QueryResult<Long> remove(Query query, QueryOptions queryOptions) throws CatalogDBException {
        throw new UnsupportedOperationException("Remove not yet implemented.");
    }

    @Override
    public QueryResult<Long> restore(Query query, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();
        query.put(QueryParams.STATUS_NAME.key(), Status.DELETED);
        return endQuery("Restore users", startTime, setStatus(query, Status.READY));
    }

    @Override
    public QueryResult<User> restore(long id, QueryOptions queryOptions) throws CatalogDBException {
        throw new CatalogDBException("Delete user by int id. The id should be a string.");
    }

    public QueryResult<User> restore(String id, QueryOptions queryOptions) throws CatalogDBException {
        long startTime = startQuery();

        checkId(id);
        Query query = new Query(QueryParams.ID.key(), id)
                .append(QueryParams.STATUS_NAME.key(), Status.DELETED);
        if (count(query).first() == 0) {
            throw new CatalogDBException("The user {" + id + "} is not deleted");
        }

        setStatus(id, Status.READY);
        query = new Query(QueryParams.ID.key(), id);

        return endQuery("Restore user", startTime, get(query, null));
    }

    /***
     * Removes completely the user from the database.
     * @param id User id to be removed from the database.
     * @return a QueryResult object with the user removed.
     * @throws CatalogDBException when there is any problem during the removal.
     */
    public QueryResult<User> clean(String id) throws CatalogDBException {
        long startTime = startQuery();
        Query query = new Query(QueryParams.ID.key(), id);
        Bson bson = parseQuery(query);

        QueryResult<User> userQueryResult = get(query, new QueryOptions());
        QueryResult<DeleteResult> remove = userCollection.remove(bson, new QueryOptions());
        if (remove.first().getDeletedCount() == 0) {
            throw CatalogDBException.idNotFound("User", query.getString(QueryParams.ID.key()));
        } else {
            return endQuery("Clean user", startTime, userQueryResult);
        }
    }

    @Override
    public DBIterator<User> iterator(Query query, QueryOptions options) throws CatalogDBException {
        Bson bson = parseQuery(query);
        MongoCursor<Document> iterator = userCollection.nativeQuery().find(bson, options).iterator();
        return new MongoDBIterator<>(iterator, userConverter);
    }

    @Override
    public DBIterator<Document> nativeIterator(Query query, QueryOptions options) throws CatalogDBException {
        Bson bson = parseQuery(query);
        MongoCursor<Document> iterator = userCollection.nativeQuery().find(bson, options).iterator();
        return new MongoDBIterator<>(iterator);
    }

    @Override
    public DBIterator<User> iterator(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return null;
    }

    @Override
    public DBIterator nativeIterator(Query query, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return null;
    }

    @Override
    public QueryResult rank(Query query, String field, int numResults, boolean asc) throws CatalogDBException {
        Bson bsonQuery = parseQuery(query);
        return rank(userCollection, bsonQuery, field, "name", numResults, asc);
    }

    @Override
    public QueryResult groupBy(Query query, String field, QueryOptions options) throws CatalogDBException {
        Bson bsonQuery = parseQuery(query);
        return groupBy(userCollection, bsonQuery, field, "name", options);
    }

    @Override
    public QueryResult groupBy(Query query, List<String> fields, QueryOptions options) throws CatalogDBException {
        Bson bsonQuery = parseQuery(query);
        return groupBy(userCollection, bsonQuery, fields, "name", options);
    }

    @Override
    public QueryResult groupBy(Query query, String field, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return null;
    }

    @Override
    public QueryResult groupBy(Query query, List<String> fields, QueryOptions options, String user)
            throws CatalogDBException, CatalogAuthorizationException {
        return null;
    }

    @Override
    public void forEach(Query query, Consumer<? super Object> action, QueryOptions options) throws CatalogDBException {
        Objects.requireNonNull(action);
        try (DBIterator<User> catalogDBIterator = iterator(query, options)) {
            while (catalogDBIterator.hasNext()) {
                action.accept(catalogDBIterator.next());
            }
        }
    }

    private Bson parseQuery(Query query) throws CatalogDBException {
        List<Bson> andBsonList = new ArrayList<>();

        fixComplexQueryParam(QueryParams.ATTRIBUTES.key(), query);
        fixComplexQueryParam(QueryParams.BATTRIBUTES.key(), query);
        fixComplexQueryParam(QueryParams.NATTRIBUTES.key(), query);

        for (Map.Entry<String, Object> entry : query.entrySet()) {
            String key = entry.getKey().split("\\.")[0];
            QueryParams queryParam = QueryParams.getParam(entry.getKey()) != null ? QueryParams.getParam(entry.getKey())
                    : QueryParams.getParam(key);
            if (queryParam == null) {
                throw new CatalogDBException("Unexpected parameter " + entry.getKey() + ". The parameter does not exist or cannot be "
                        + "queried for.");
            }
            try {
                switch (queryParam) {
                    case ID:
                        addOrQuery(PRIVATE_ID, queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    case ATTRIBUTES:
                        addAutoOrQuery(entry.getKey(), entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case BATTRIBUTES:
                        String mongoKey = entry.getKey().replace(QueryParams.BATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case NATTRIBUTES:
                        mongoKey = entry.getKey().replace(QueryParams.NATTRIBUTES.key(), QueryParams.ATTRIBUTES.key());
                        addAutoOrQuery(mongoKey, entry.getKey(), query, queryParam.type(), andBsonList);
                        break;
                    case NAME:
                    case EMAIL:
                    case PASSWORD:
                    case ORGANIZATION:
                    case STATUS_NAME:
                    case STATUS_MSG:
                    case STATUS_DATE:
                    case LAST_MODIFIED:
                    case SIZE:
                    case QUOTA:
                    case PROJECTS:
                    case PROJECTS_UID:
                    case PROJECT_NAME:
                    case PROJECTS_ID:
                    case PROJECT_ORGANIZATION:
                    case PROJECT_STATUS:
                    case PROJECT_LAST_MODIFIED:
                    case TOOL_ID:
                    case TOOL_NAME:
                    case TOOL_ALIAS:
                    case CONFIGS:
                    case CONFIGS_FILTERS:
                    case CONFIGS_FILTERS_NAME:
                        addAutoOrQuery(queryParam.key(), queryParam.key(), query, queryParam.type(), andBsonList);
                        break;
                    default:
                        throw new CatalogDBException("Cannot query by parameter " + queryParam.key());
                }
            } catch (Exception e) {
                throw new CatalogDBException(e);
            }
        }

        if (andBsonList.size() > 0) {
            return Filters.and(andBsonList);
        } else {
            return new Document();
        }
    }

    public MongoDBCollection getUserCollection() {
        return userCollection;
    }
}
