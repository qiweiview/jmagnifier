package com.capture;

import com.model.CaptureConfig;
import org.junit.Assert;
import org.junit.Test;

public class CaptureOptionsTest {

    @Test
    public void shouldFallbackPreviewBytesToLegacyMaxCaptureBytes() {
        CaptureConfig config = new CaptureConfig();
        config.setPreviewBytes(null);
        config.setMaxCaptureBytes(2048);

        CaptureOptions options = new CaptureOptions(config);

        Assert.assertEquals(2048, options.getPreviewBytes());
        Assert.assertEquals(PayloadStoreType.PREVIEW_ONLY, options.getPayloadStoreType());
    }

    @Test
    public void shouldUseExplicitPayloadStoreOptions() {
        CaptureConfig config = new CaptureConfig();
        config.setPreviewBytes(4096);
        config.setPayloadStoreType("FILE");
        config.setMaxPayloadBytes(8192);

        CaptureOptions options = new CaptureOptions(config);

        Assert.assertEquals(4096, options.getPreviewBytes());
        Assert.assertEquals(PayloadStoreType.FILE, options.getPayloadStoreType());
        Assert.assertEquals(8192, options.getMaxPayloadBytes());
    }
}
