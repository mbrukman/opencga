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

package org.opencb.opencga.catalog.monitor;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectReader;
import org.codehaus.jackson.type.TypeReference;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.opencga.catalog.db.api.JobDBAdaptor;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.exceptions.CatalogIOException;
import org.opencb.opencga.catalog.io.CatalogIOManager;
import org.opencb.opencga.catalog.managers.CatalogManager;
import org.opencb.opencga.core.models.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Created by pfurio on 26/08/16.
 */
@Deprecated
public class VariantIndexOutputRecorder {

    private CatalogManager catalogManager;
    private CatalogIOManager catalogIOManager;
    private ObjectReader objectReader;
    private String sessionId;
    private static Logger logger = LoggerFactory.getLogger(VariantIndexOutputRecorder.class);

    public VariantIndexOutputRecorder(CatalogManager catalogManager, CatalogIOManager catalogIOManager, String sessionId) {
        this.catalogManager = catalogManager;
        this.catalogIOManager = catalogIOManager;
        this.sessionId = sessionId;
        this.objectReader = new ObjectMapper().reader(new TypeReference<List<Map>>(){});
    }

    @Deprecated
    public void registerStorageETLResults(Job job, Path tmpOutdirPath) {
        logger.debug("Updating storage ETL Results");
        File fileResults = tmpOutdirPath.resolve("storageETLresults").toFile();
        if (fileResults.exists()) {
            Object storageETLresults;
            ObjectMap params = new ObjectMap(job.getAttributes());
            try {
                storageETLresults = objectReader.readValue(tmpOutdirPath.resolve("storageETLresults").toFile());
                params .putIfNotNull("storageETLResult", storageETLresults);
                ObjectMap attributes = new ObjectMap(JobDBAdaptor.QueryParams.ATTRIBUTES.key(), params);
                catalogManager.getJobManager().update(job.getUid(), attributes, new QueryOptions(), sessionId);
                job.setAttributes(params);
            } catch (IOException e) {
                logger.error("Error reading the storageResults from {}", fileResults);
            } catch (CatalogException e) {
                logger.error("Could not update job {} with params {}", job.getUid(), params.safeToString());
            } finally {
                try {
                    catalogIOManager.deleteFile(fileResults.toURI());
                } catch (CatalogIOException e) {
                    logger.error("Could not delete storageResults file {}", fileResults);
                }
            }
        }
    }

}
