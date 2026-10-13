package ai.chat2db.community.web.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import ai.chat2db.community.domain.api.model.task.ImportStagingLimits;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.web.api.adapter.db.ImportUploadCapabilityAdapter;
import jakarta.servlet.MultipartConfigElement;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class ImportUploadCapabilitiesTest {
    private static final long MIB = 1024L * 1024;

    private ImportUploadCapabilityAdapter adapter(long file, long total, int count,
            MultipartConfigElement multipart) {
        var staging = (IImportFileStagingService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IImportFileStagingService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("uploadLimits")) return new ImportStagingLimits(file, total, count);
                    throw new AssertionError("Capabilities must not access files or databases");
                });
        var beans = new DefaultListableBeanFactory();
        if (multipart != null) beans.registerSingleton("multipartConfig", multipart);
        return new ImportUploadCapabilityAdapter(staging, beans.getBeanProvider(MultipartConfigElement.class));
    }

    @Test
    void defaultLimitsPreserveDistinctSingleAndMultiPolicies() {
        var limits = adapter(1024*MIB, 10*1024*MIB, 1000,
                new MultipartConfigElement("", 1024*MIB, 1025*MIB, 0)).capabilities();
        assertEquals(50*MIB, limits.singleFileMaxBytes());
        assertEquals(1024*MIB, limits.multiFileMaxBytes());
        assertEquals(10*1024*MIB, limits.multiTotalMaxBytes());
        assertEquals(1000, limits.maxFiles());
    }

    @Test
    void reducedMultipartRequestBudgetConstrainsBrowserButNotDesktop() {
        var limits = adapter(20*MIB, 30*MIB, 3,
                new MultipartConfigElement("", 10*MIB, 7*MIB, 0)).capabilities();
        assertEquals(6*MIB, limits.singleFileMaxBytes());
        assertEquals(6*MIB, limits.multiFileMaxBytes());
        assertEquals(20*MIB, limits.singleLocalFileMaxBytes());
        assertEquals(20*MIB, limits.multiLocalFileMaxBytes());
        assertEquals(30*MIB, limits.multiTotalMaxBytes());
        assertEquals(3, limits.maxFiles());
    }

    @Test
    void unlimitedMultipartCannotRaiseProductOrStagingCaps() {
        var limits = adapter(2048*MIB, 20*1024*MIB, 2000,
                new MultipartConfigElement("", -1, -1, 0)).capabilities();
        assertEquals(50*MIB, limits.singleFileMaxBytes());
        assertEquals(1024*MIB, limits.multiFileMaxBytes());
        assertEquals(10*1024*MIB, limits.multiTotalMaxBytes());
        assertEquals(1000, limits.maxFiles());
    }

    @Test
    void totalQuotaAndAbsentMultipartRemainConservative() {
        var limits = adapter(100*MIB, 2*MIB, 2, null).capabilities();
        assertEquals(0, limits.multiFileMaxBytes());
        assertEquals(2*MIB, limits.multiLocalFileMaxBytes());
        assertEquals(2*MIB, limits.singleLocalFileMaxBytes());
    }

    @Test
    void multipartFileLimitIsAnIndependentCeiling() {
        var limits = adapter(1024*MIB, 10*1024*MIB, 1000,
                new MultipartConfigElement("", 3*MIB, 100*MIB, 0)).capabilities();
        assertEquals(3*MIB, limits.multiFileMaxBytes());
        assertEquals(3*MIB, limits.multipartMaxFileBytes());
        assertEquals(MIB, limits.multipartOverheadBytes());
    }

    @Test
    void controllerReturnsExistingDataEnvelopeAndByteFieldsWithoutDatasource() throws Exception {
        var controller = new DbImportPreviewController(null, null, null, null, null);
        var field = DbImportPreviewController.class.getDeclaredField("uploadCapabilityAdapter");
        field.setAccessible(true);
        field.set(controller, adapter(1024*MIB, 10*1024*MIB, 1000,
                new MultipartConfigElement("", 1024*MIB, 1025*MIB, 0)));
        var result = controller.capabilities();
        assertTrue(result.getSuccess());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(52428800, response.path("data").path("singleFileMaxBytes").asLong());
        assertEquals(1073741824, response.path("data").path("multiFileMaxBytes").asLong());
        assertEquals(10737418240L, response.path("data").path("multiTotalMaxBytes").asLong());
    }

    @Test
    void requestSmallerThanEnvelopeDisablesWebAdmissionWithoutNegativeBudgets() {
        var limits = adapter(1024*MIB, 10*1024*MIB, 1000,
                new MultipartConfigElement("", 10*MIB, 100, 0)).capabilities();
        assertEquals(0, limits.singleFileMaxBytes());
        assertEquals(0, limits.multiFileMaxBytes());
        assertEquals(50*MIB, limits.singleLocalFileMaxBytes());
    }
    @Test
    void exposesDatabaseIndependentUploadCapabilities() {
        var endpoint = java.util.Arrays.stream(DbImportPreviewController.class.getMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .filter(method -> java.util.Arrays.asList(method.getAnnotation(GetMapping.class).value())
                        .contains("/capabilities")).findFirst();
        assertTrue(endpoint.isPresent(), "GET /api/rdb/import_preview/capabilities must expose actual upload limits");
    }
}
