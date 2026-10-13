package ai.chat2db.community.web.api.model.response.db;

/** Absolute configured ceilings; concurrent staging use can still cause server rejection. */
public record ImportUploadCapabilities(long singleFileMaxBytes, long multiFileMaxBytes,
        long singleLocalFileMaxBytes, long multiLocalFileMaxBytes, long multiTotalMaxBytes,
        int maxFiles, long stagingMaxFileBytes, long stagingMaxTotalBytes,
        long multipartMaxFileBytes, long multipartMaxRequestBytes, long multipartOverheadBytes) { }
