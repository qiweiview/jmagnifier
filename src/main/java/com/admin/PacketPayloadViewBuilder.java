package com.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.store.PacketRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class PacketPayloadViewBuilder {

    private static final byte[] CRLF_CRLF = new byte[]{'\r', '\n', '\r', '\n'};

    private static final byte[] LF_LF = new byte[]{'\n', '\n'};

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int previewBytes;

    public PacketPayloadViewBuilder(int previewBytes) {
        this.previewBytes = previewBytes;
    }

    public PacketPayloadView build(PacketRepository.PacketRecord record) {
        byte[] payload = record == null || record.payload == null ? new byte[0] : record.payload;
        int visibleLength = previewBytes <= 0 ? payload.length : Math.min(payload.length, previewBytes);
        byte[] preview = Arrays.copyOf(payload, visibleLength);
        boolean previewTruncated = previewBytes > 0 && payload.length > previewBytes;
        return new PacketPayloadView(
                decodeText(preview),
                hexPreview(preview),
                visibleLength,
                previewTruncated,
                buildHttpView(record, preview, previewTruncated)
        );
    }

    private HttpPayloadView buildHttpView(PacketRepository.PacketRecord record, byte[] preview, boolean previewTruncated) {
        if (!isHttp(record)) {
            return null;
        }
        Separator separator = findSeparator(preview);
        if (separator == null) {
            HeaderBlock headerBlock = parseHeaderBlock(decodeText(preview));
            return new HttpPayloadView(
                    true,
                    headerBlock.startLine,
                    headerBlock.headersText,
                    null,
                    false,
                    isJsonContentType(resolveContentType(record, headerBlock.headersText)),
                    null,
                    record != null && record.truncated || previewTruncated
            );
        }
        String headerText = decodeText(Arrays.copyOfRange(preview, 0, separator.index));
        HeaderBlock headerBlock = parseHeaderBlock(headerText);
        byte[] bodyBytes = Arrays.copyOfRange(preview, separator.index + separator.length, preview.length);
        boolean bodyDetected = bodyBytes.length > 0;
        boolean bodyTruncated = record != null && record.truncated || previewTruncated;
        String contentType = resolveContentType(record, headerBlock.headersText);
        String contentEncoding = resolveHeaderValue(headerBlock.headersText, "content-encoding");
        String bodyText = bodyDetected ? decodeBodyText(bodyBytes, contentEncoding, bodyTruncated) : null;
        boolean bodyJson = bodyDetected && isJsonContentType(contentType);
        String bodyJsonPretty = null;
        if (bodyJson && !bodyTruncated) {
            bodyJsonPretty = tryPrettyJson(bodyText);
        }
        return new HttpPayloadView(
                true,
                headerBlock.startLine,
                headerBlock.headersText,
                bodyText,
                bodyDetected,
                bodyJson,
                bodyJsonPretty,
                bodyTruncated
        );
    }

    private String resolveContentType(PacketRepository.PacketRecord record, String headersText) {
        if (record != null && notBlank(record.contentType)) {
            return record.contentType;
        }
        return resolveHeaderValue(headersText, "content-type");
    }

    private String resolveHeaderValue(String headersText, String headerName) {
        if (!notBlank(headersText)) {
            return null;
        }
        String[] lines = headersText.split("\\r?\\n");
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            if (headerName.equalsIgnoreCase(name)) {
                return line.substring(separator + 1).trim();
            }
        }
        return null;
    }

    private String decodeBodyText(byte[] bodyBytes, String contentEncoding, boolean bodyTruncated) {
        if (!bodyTruncated && notBlank(contentEncoding)) {
            byte[] decoded = tryDecodeContentEncoding(bodyBytes, contentEncoding);
            if (decoded != null) {
                return decodeText(decoded);
            }
        }
        return decodeText(bodyBytes);
    }

    private byte[] tryDecodeContentEncoding(byte[] bodyBytes, String contentEncoding) {
        byte[] decoded = bodyBytes;
        String[] encodings = contentEncoding.split(",");
        for (int i = encodings.length - 1; i >= 0; i--) {
            String encoding = encodings[i] == null ? null : encodings[i].trim().toLowerCase(Locale.ROOT);
            if (!notBlank(encoding) || "identity".equals(encoding)) {
                continue;
            }
            if ("gzip".equals(encoding) || "x-gzip".equals(encoding)) {
                decoded = gunzip(decoded);
            } else if ("deflate".equals(encoding)) {
                decoded = inflate(decoded);
            } else {
                return null;
            }
            if (decoded == null) {
                return null;
            }
        }
        return decoded;
    }

    private byte[] gunzip(byte[] compressed) {
        try {
            return readAll(new GZIPInputStream(new ByteArrayInputStream(compressed)));
        } catch (IOException ignore) {
            return null;
        }
    }

    private byte[] inflate(byte[] compressed) {
        byte[] decoded = inflate(compressed, false);
        return decoded != null ? decoded : inflate(compressed, true);
    }

    private byte[] inflate(byte[] compressed, boolean nowrap) {
        Inflater inflater = new Inflater(nowrap);
        try {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(compressed), inflater));
        } catch (IOException ignore) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private byte[] readAll(java.io.InputStream inputStream) throws IOException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } finally {
            inputStream.close();
        }
    }

    private String tryPrettyJson(String bodyText) {
        if (!notBlank(bodyText)) {
            return null;
        }
        String normalized = stripBom(bodyText).trim();
        if (normalized.length() == 0) {
            return null;
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(normalized);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
        } catch (IOException ignore) {
            return null;
        }
    }

    private String stripBom(String value) {
        if (value != null && value.length() > 0 && value.charAt(0) == '\ufeff') {
            return value.substring(1);
        }
        return value;
    }

    private boolean isHttp(PacketRepository.PacketRecord record) {
        if (record == null) {
            return false;
        }
        return equalsIgnoreCase(record.protocolFamily, "HTTP")
                || equalsIgnoreCase(record.applicationProtocol, "http1")
                || equalsIgnoreCase(record.applicationProtocol, "http");
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && left.equalsIgnoreCase(right);
    }

    private boolean isJsonContentType(String contentType) {
        if (!notBlank(contentType)) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).trim();
        }
        return "application/json".equals(normalized)
                || normalized.startsWith("application/")
                && normalized.endsWith("+json");
    }

    private String decodeText(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String hexPreview(byte[] payload) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < payload.length; i++) {
            if (i > 0) {
                builder.append(i % 16 == 0 ? '\n' : ' ');
            }
            int value = payload[i] & 0xff;
            if (value < 16) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private Separator findSeparator(byte[] payload) {
        int index = indexOf(payload, CRLF_CRLF);
        if (index >= 0) {
            return new Separator(index, CRLF_CRLF.length);
        }
        index = indexOf(payload, LF_LF);
        if (index >= 0) {
            return new Separator(index, LF_LF.length);
        }
        return null;
    }

    private int indexOf(byte[] payload, byte[] pattern) {
        if (payload.length == 0 || pattern.length == 0 || payload.length < pattern.length) {
            return -1;
        }
        for (int i = 0; i <= payload.length - pattern.length; i++) {
            boolean matched = true;
            for (int j = 0; j < pattern.length; j++) {
                if (payload[i + j] != pattern[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    private HeaderBlock parseHeaderBlock(String headerText) {
        if (!notBlank(headerText)) {
            return new HeaderBlock(null, null);
        }
        int lineBreak = headerText.indexOf("\r\n");
        int lineBreakLength = 2;
        if (lineBreak < 0) {
            lineBreak = headerText.indexOf('\n');
            lineBreakLength = 1;
        }
        if (lineBreak < 0) {
            return new HeaderBlock(headerText, null);
        }
        String startLine = headerText.substring(0, lineBreak);
        String headersText = headerText.substring(lineBreak + lineBreakLength);
        if (!notBlank(headersText)) {
            headersText = null;
        }
        return new HeaderBlock(startLine, headersText);
    }

    private boolean notBlank(String value) {
        return value != null && value.trim().length() > 0;
    }

    private static class Separator {

        private final int index;

        private final int length;

        private Separator(int index, int length) {
            this.index = index;
            this.length = length;
        }
    }

    private static class HeaderBlock {

        private final String startLine;

        private final String headersText;

        private HeaderBlock(String startLine, String headersText) {
            this.startLine = startLine;
            this.headersText = headersText;
        }
    }

    public static class PacketPayloadView {

        public final String textRaw;

        public final String hex;

        public final int previewBytes;

        public final boolean previewTruncated;

        public final HttpPayloadView http;

        private PacketPayloadView(String textRaw, String hex, int previewBytes, boolean previewTruncated, HttpPayloadView http) {
            this.textRaw = textRaw;
            this.hex = hex;
            this.previewBytes = previewBytes;
            this.previewTruncated = previewTruncated;
            this.http = http;
        }
    }

    public static class HttpPayloadView {

        public final boolean isHttp;

        public final String startLine;

        public final String headersText;

        public final String bodyText;

        public final boolean bodyDetected;

        public final boolean bodyJson;

        public final String bodyJsonPretty;

        public final boolean bodyTruncated;

        private HttpPayloadView(boolean isHttp, String startLine, String headersText, String bodyText,
                                boolean bodyDetected, boolean bodyJson, String bodyJsonPretty, boolean bodyTruncated) {
            this.isHttp = isHttp;
            this.startLine = startLine;
            this.headersText = headersText;
            this.bodyText = bodyText;
            this.bodyDetected = bodyDetected;
            this.bodyJson = bodyJson;
            this.bodyJsonPretty = bodyJsonPretty;
            this.bodyTruncated = bodyTruncated;
        }
    }
}
