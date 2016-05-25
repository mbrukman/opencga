package org.opencb.opencga.analysis.files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.opencb.commons.datastore.core.Query;
import org.opencb.opencga.catalog.CatalogManager;
import org.opencb.opencga.catalog.CatalogManagerExternalResource;
import org.opencb.opencga.catalog.CatalogManagerTest;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.models.File;
import org.opencb.opencga.catalog.models.Project;
import org.opencb.opencga.catalog.models.Study;
import org.opencb.opencga.core.common.IOUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class FileScannerTest {
    @Rule
    public CatalogManagerExternalResource catalogManagerExternalResource = new CatalogManagerExternalResource();

    public static final String PASSWORD = "asdf";
    private CatalogManager catalogManager;
    private String sessionIdUser;
    private File folder;
    private Study study;
    private Project project;
    private final Path directory = Paths.get("/tmp/catalog_scan_test_folder");

    @Before
    public void setUp() throws IOException, CatalogException {
        catalogManager = catalogManagerExternalResource.getCatalogManager();

        catalogManager.createUser("user", "User Name", "mail@ebi.ac.uk", PASSWORD, "", null, null);
        sessionIdUser = catalogManager.login("user", PASSWORD, "127.0.0.1").first().getString("sessionId");
        project = catalogManager.createProject("user", "Project about some genomes", "1000G", "", "ACME", null, sessionIdUser).first();
        study = catalogManager.createStudy(project.getId(), "Phase 1", "phase1", Study.Type.TRIO, "Done", sessionIdUser).first();
        folder = catalogManager.createFolder(study.getId(), Paths.get("data/test/folder/"), true, null, sessionIdUser).first();

        if (directory.toFile().exists()) {
            IOUtils.deleteDirectory(directory);
        }
        Files.createDirectory(directory);
    }

    @Test
    public void testScan() throws IOException, CatalogException {

        Files.createDirectory(Paths.get("/tmp/catalog_scan_test_folder/subfolder"));
        Files.createDirectory(Paths.get("/tmp/catalog_scan_test_folder/subfolder/subsubfolder"));
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file2.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file3.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/subfolder/file1.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/subfolder/file2.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/subfolder/file3.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/subfolder/subsubfolder/file1.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/subfolder/subsubfolder/file2.txt");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/subfolder/subsubfolder/file3.txt");

        FileScanner fileScanner = new FileScanner(catalogManager);
        List<File> files = fileScanner.scan(folder, directory.toUri(), FileScanner.FileScannerPolicy.DELETE, true, true, sessionIdUser);

        assertEquals(9, files.size());
        files.forEach((File file) -> assertTrue(file.getAttributes().containsKey("checksum")));

    }

    @Test
    public void testDeleteExisting() throws IOException, CatalogException {


        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.txt");


        File file = catalogManager.createFile(study.getId(), File.Format.PLAIN, File.Bioformat.NONE, folder.getPath() + "file1.txt",
                CatalogManagerTest.createDebugFile().toURI(), "", false, sessionIdUser).first();

        List<File> files = new FileScanner(catalogManager).scan(folder, directory.toUri(), FileScanner.FileScannerPolicy.DELETE, false, true, sessionIdUser);

        files.forEach((File f) -> assertFalse(f.getAttributes().containsKey("checksum")));
        assertEquals(File.FileStatus.TRASHED, catalogManager.getFile(file.getId(), sessionIdUser).first().getStatus().getStatus());
    }

    @Test
    public void testDeleteTrashed() throws IOException, CatalogException {

        File file = catalogManager.createFile(study.getId(), File.Format.PLAIN, File.Bioformat.NONE, folder.getPath() + "file1.txt",
                CatalogManagerTest.createDebugFile().toURI(), "", false, sessionIdUser).first();

        catalogManager.deleteFile(file.getId(), sessionIdUser);

        file = catalogManager.getFile(file.getId(), sessionIdUser).first();
        assertEquals(File.FileStatus.TRASHED, file.getStatus().getStatus());

        Files.delete(Paths.get(catalogManager.getFileUri(file)));
        List<File> files = new FileScanner(catalogManager).checkStudyFiles(study, false, sessionIdUser);

        file = catalogManager.getFile(file.getId(), sessionIdUser).first();
        assertEquals(File.FileStatus.DELETED, file.getStatus().getStatus());
        assertEquals(1, files.size());
        assertEquals(file.getId(), files.get(0).getId());
    }

    @Test
    public void testReplaceExisting() throws IOException, CatalogException {
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.txt");

        File file = catalogManager.createFile(study.getId(), File.Format.PLAIN, File.Bioformat.NONE, folder.getPath() + "file1.txt",
                CatalogManagerTest.createDebugFile().toURI(), "", false, sessionIdUser).first();

        FileScanner fileScanner = new FileScanner(catalogManager);
        fileScanner.scan(folder, directory.toUri(), FileScanner.FileScannerPolicy.REPLACE, true, true, sessionIdUser);

        File replacedFile = catalogManager.getFile(file.getId(), sessionIdUser).first();
        assertEquals(File.FileStatus.READY, replacedFile.getStatus().getStatus());
        assertEquals(file.getId(), replacedFile.getId());
        assertFalse(replacedFile.getAttributes().get("checksum").equals(file.getAttributes().get("checksum")));
    }


    @Test
    public void testScanStudyURI() throws IOException, CatalogException {

        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.txt");

        FileScanner fileScanner = new FileScanner(catalogManager);
        List<File> files = fileScanner.scan(folder, directory.toUri(), FileScanner.FileScannerPolicy.REPLACE, true, true, sessionIdUser);
        assertEquals(1, files.size());

        URI studyUri = catalogManager.getStudyUri(study.getId());
        CatalogManagerTest.createDebugFile(studyUri.resolve("data/test/folder/").resolve("file2.txt").getPath());
        File root = catalogManager.searchFile(study.getId(), new Query("name", "."), sessionIdUser).first();
        files = fileScanner.scan(root, studyUri, FileScanner.FileScannerPolicy.REPLACE, true, true, sessionIdUser);

        assertEquals(1, files.size());
        files.forEach((f) -> assertTrue(f.getDiskUsage() > 0));
        files.forEach((f) -> assertEquals(f.getStatus().getStatus(), File.FileStatus.READY));
        files.forEach((f) -> assertTrue(f.getAttributes().containsKey("checksum")));
    }

    @Test
    public void testResyncStudy() throws IOException, CatalogException {
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.txt");

        //ReSync study folder. Will detect any difference.
        FileScanner fileScanner = new FileScanner(catalogManager);
        List<File> files;
        files = fileScanner.reSync(study, true, sessionIdUser);
        assertEquals(0, files.size());

        //Add one extra file. ReSync study folder.
        URI studyUri = catalogManager.getStudyUri(study.getId());
        Path filePath = CatalogManagerTest.createDebugFile(studyUri.resolve("data/test/folder/").resolve("file_scanner_test_file.txt").getPath()).toPath();
        files = fileScanner.reSync(study, true, sessionIdUser);

        assertEquals(1, files.size());
        File file = files.get(0);
        assertTrue(file.getDiskUsage() > 0);
        assertEquals(File.FileStatus.READY, file.getStatus().getStatus());
        assertTrue(file.getAttributes().containsKey("checksum"));


        //Delete file. CheckStudyFiles. Will detect one File.Status.MISSING file
        Files.delete(filePath);
        files = fileScanner.checkStudyFiles(study, true, sessionIdUser);

        assertEquals(1, files.size());
        assertEquals(File.FileStatus.MISSING, files.get(0).getStatus().getStatus());
        String originalChecksum = files.get(0).getAttributes().get("checksum").toString();

        //Restore file. CheckStudyFiles. Will detect one re-tracked file. Checksum must be different.
        CatalogManagerTest.createDebugFile(filePath.toString());
        files = fileScanner.checkStudyFiles(study, true, sessionIdUser);

        assertEquals(1, files.size());
        assertEquals(File.FileStatus.READY, files.get(0).getStatus().getStatus());
        String newChecksum = files.get(0).getAttributes().get("checksum").toString();
        assertFalse(originalChecksum.equals(newChecksum));

        //Delete file. ReSync. Will detect one File.Status.MISSING file (like checkFile)
        Files.delete(filePath);
        files = fileScanner.reSync(study, true, sessionIdUser);

        assertEquals(1, files.size());
        assertEquals(File.FileStatus.MISSING, files.get(0).getStatus().getStatus());
        originalChecksum = files.get(0).getAttributes().get("checksum").toString();

        //Restore file. CheckStudyFiles. Will detect one found file. Checksum must be different.
        CatalogManagerTest.createDebugFile(filePath.toString());
        files = fileScanner.reSync(study, true, sessionIdUser);

        assertEquals(1, files.size());
        assertEquals(File.FileStatus.READY, files.get(0).getStatus().getStatus());
        newChecksum = files.get(0).getAttributes().get("checksum").toString();
        assertFalse(originalChecksum.equals(newChecksum));

    }

    @Test
    public void testComplexAdd() throws IOException, CatalogException {

        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.vcf.gz");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.vcf.variants.json");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.vcf.variants.json.gz");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file1.vcf.variants.json.snappy");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file2.bam");
        CatalogManagerTest.createDebugFile("/tmp/catalog_scan_test_folder/file2.sam.gz");

        FileScanner fileScanner = new FileScanner(catalogManager);
        List<File> files = fileScanner.scan(folder, directory.toUri(), FileScanner.FileScannerPolicy.REPLACE, true, true, sessionIdUser);

        Map<String, File> map = files.stream().collect(Collectors.toMap(File::getName, (f) -> f));

        assertEquals(6, files.size());
        files.forEach((file) -> assertEquals(File.FileStatus.READY, file.getStatus().getStatus()));
        assertEquals(File.Bioformat.VARIANT, map.get("file1.vcf.gz").getBioformat());
        assertEquals(File.Bioformat.VARIANT, map.get("file1.vcf.variants.json").getBioformat());
        assertEquals(File.Bioformat.VARIANT, map.get("file1.vcf.variants.json.gz").getBioformat());
        assertEquals(File.Bioformat.VARIANT, map.get("file1.vcf.variants.json.snappy").getBioformat());
        assertEquals(File.Bioformat.ALIGNMENT, map.get("file2.bam").getBioformat());
        assertEquals(File.Bioformat.ALIGNMENT, map.get("file2.sam.gz").getBioformat());

        assertEquals(File.Format.VCF, map.get("file1.vcf.gz").getFormat());
        assertEquals(File.Format.JSON, map.get("file1.vcf.variants.json").getFormat());
        assertEquals(File.Format.JSON, map.get("file1.vcf.variants.json.gz").getFormat());
        assertEquals(File.Format.JSON, map.get("file1.vcf.variants.json.snappy").getFormat());
        assertEquals(File.Format.BAM, map.get("file2.bam").getFormat());
        assertEquals(File.Format.SAM, map.get("file2.sam.gz").getFormat());

    }


}