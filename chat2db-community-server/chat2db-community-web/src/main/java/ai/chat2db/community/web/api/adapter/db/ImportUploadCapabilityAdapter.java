package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.web.api.model.response.db.ImportUploadCapabilities;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Combines domain capacity with the active servlet multipart configuration and UI policies. */
@Component
public class ImportUploadCapabilityAdapter {
    private static final long MIB = 1024L * 1024L;
    private final IImportFileStagingService staging;
    private final ObjectProvider<MultipartConfigElement> multipart;

    public ImportUploadCapabilityAdapter(IImportFileStagingService staging,
            ObjectProvider<MultipartConfigElement> multipart) {
        this.staging = staging;
        this.multipart = multipart;
    }

    public ImportUploadCapabilities capabilities() {
        var limits = staging.uploadLimits();
        var config = multipart.getIfAvailable();
        // No servlet config means browser transport cannot be advertised as available.
        long file = config == null ? 0 : config.getMaxFileSize();
        long request = config == null ? 0 : config.getMaxRequestSize();
        long total = Math.min(10L * 1024 * MIB, limits.maxTotalBytes());
        long local = Math.min(Math.min(1024 * MIB, limits.maxFileBytes()), total);
        // A multipart envelope consumes request bytes too. Each upload request holds one file.
        long requestBudget = request < 0 ? Long.MAX_VALUE : Math.max(0, request - MIB);
        long web = Math.min(local, Math.min(file < 0 ? Long.MAX_VALUE : file, requestBudget));
        return new ImportUploadCapabilities(Math.min(50 * MIB, web), web,
                Math.min(50 * MIB, local), local, total, Math.min(1000, limits.maxFiles()),
                limits.maxFileBytes(), limits.maxTotalBytes(), file, request, MIB);
    }
}
