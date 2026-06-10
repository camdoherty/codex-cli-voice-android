package io.github.codex_cli_voice_android.aecshim;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ShareActivity extends Activity {
    private static final int MAX_ATTACHMENT_BYTES = 384 * 1024;
    private static final int MAX_TOTAL_ENCODED_BYTES = 700 * 1024;
    private static final int MAX_TEXT_CHARS = 64 * 1024;

    private final List<SharedFile> files = new ArrayList<>();
    private final List<String> filenames = new ArrayList<>();
    private final List<String> textParts = new ArrayList<>();
    private final List<String> fullTextParts = new ArrayList<>();
    private int encodedBytes;
    private int textChars;
    private int fullTextChars;
    private boolean textTruncated;
    private boolean fullTextAttachmentPrepared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleShare(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
        finish();
    }

    protected boolean reviewNow() {
        return false;
    }

    private void handleShare(Intent intent) {
        if (intent == null) {
            TermuxCommandLauncher.showShareFailureNotification(this, "Nothing was shared to Codex Bridge.");
            toast("Nothing shared to Codex Bridge.");
            return;
        }
        try {
            collectSharedText(intent);
            collectSharedUris(intent);
            if (textParts.isEmpty() && files.isEmpty()) {
                TermuxCommandLauncher.showShareFailureNotification(this, "Codex Bridge could not read the shared item.");
                toast("Codex Bridge could not read the shared item.");
                return;
            }
            String itemId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + "-android-share";
            boolean review = reviewNow();
            TermuxCommandLauncher.showSharePendingNotification(this, review);
            if (TermuxCommandLauncher.runSharedIntake(this, buildTermuxCommand(itemId, review), itemId, review)) {
                toast(review ? "Reviewing shared item..." : "Saving to Codex Inbox...");
            } else {
                TermuxCommandLauncher.showShareFailureNotification(this, "Codex Bridge needs Termux controls setup.");
                toast("Codex Bridge needs Termux controls setup.");
            }
        } catch (Exception e) {
            TermuxCommandLauncher.showShareFailureNotification(this, "Codex Bridge share failed: " + safeMessage(e));
            toast("Codex Bridge share failed: " + safeMessage(e));
        }
    }

    private void collectSharedText(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null && text.length() > 0) {
            addTextPart(text.toString());
        }
        CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        if (subject != null && subject.length() > 0) {
            addTextPart("Subject: " + subject);
        }
    }

    private void collectSharedUris(Intent intent) throws Exception {
        ArrayList<Uri> streamUris = new ArrayList<>();
        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) {
            streamUris.add(stream);
        }
        ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (streams != null) {
            for (Uri uri : streams) {
                if (uri != null && !streamUris.contains(uri)) {
                    streamUris.add(uri);
                }
            }
        }
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null && !streamUris.contains(uri)) {
                    streamUris.add(uri);
                }
                CharSequence text = clipData.getItemAt(i).getText();
                if (text != null && text.length() > 0) {
                    addTextPart(text.toString());
                }
            }
        }
        for (Uri uri : streamUris) {
            files.add(readSharedFile(uri));
        }
    }

    private SharedFile readSharedFile(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        String name = uniqueFilename(sanitizeFilename(displayNameFor(uri)));
        String mimeType = resolver.getType(uri);
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = "application/octet-stream";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                return SharedFile.metadataOnly(name, mimeType, uri.toString(), "openInputStream returned null");
            }
            byte[] buffer = new byte[8192];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (output.size() + read > MAX_ATTACHMENT_BYTES) {
                    truncated = true;
                    break;
                }
                output.write(buffer, 0, read);
            }
        }
        if (truncated) {
            return SharedFile.metadataOnly(
                    name,
                    mimeType,
                    uri.toString(),
                    "file exceeds " + MAX_ATTACHMENT_BYTES + " byte Codex Bridge intake limit");
        }
        byte[] data = output.toByteArray();
        String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
        encodedBytes += encoded.length();
        if (encodedBytes > MAX_TOTAL_ENCODED_BYTES) {
            return SharedFile.metadataOnly(
                    name,
                    mimeType,
                    uri.toString(),
                    "share exceeds " + MAX_TOTAL_ENCODED_BYTES + " byte Codex Bridge command limit");
        }
        return new SharedFile(name, mimeType, uri.toString(), encoded, data.length, "");
    }

    private String buildTermuxCommand(String itemId, boolean review) {
        prepareFullTextAttachment();

        String inboxExpr = "\"$HOME/storage/shared/Documents/codex_notes/inbox\"";
        String fallbackExpr = "\"$HOME/codex_notes/inbox\"";
        String itemDirExpr = "\"$inbox/" + itemId + "\"";

        StringBuilder command = new StringBuilder();
        command.append("set -eu\n");
        command.append("inbox=").append(inboxExpr).append("\n");
        command.append("[ -d \"$HOME/storage/shared/Documents\" ] || inbox=").append(fallbackExpr).append("\n");
        command.append("item=").append(itemDirExpr).append("\n");
        command.append("mkdir -p \"$item/attachments\"\n");
        writeBase64File(command, "$item/payload.md", payloadMarkdown());
        writeBase64File(command, "$item/manifest.json", manifestJson(itemId));
        for (SharedFile file : files) {
            if (!file.encodedContent.isEmpty()) {
                writeEncodedFile(command, "$item/attachments/" + shellSingleQuote(file.name), file.encodedContent);
            }
        }
        command.append("state=\"$HOME/.local/state/codex-stts\"\n");
        command.append("mkdir -p \"$state\"\n");
        command.append("printf '%s\\n' \"$item/manifest.json\" > \"$state/latest-share-manifest.txt\"\n");
        command.append("printf '%s\\n' \"$item\" > \"$state/latest-share-dir.txt\"\n");
        command.append("printf 'saved %s\\n' \"$item/manifest.json\"\n");
        if (review) {
            command.append("resume_wake=0\n");
            command.append("pid=$(cat \"$state/session.pid\" 2>/dev/null || true)\n");
            command.append("mode=$(cat \"$state/session-mode.txt\" 2>/dev/null || true)\n");
            command.append("if [ \"$mode\" = wake ] && [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then resume_wake=1; fi\n");
            command.append("if [ \"$resume_wake\" = 1 ]; then wake_arg=--then-wake; else wake_arg=; fi\n");
            command.append("if command -v stts >/dev/null 2>&1; then stts stop >/dev/null 2>&1 || true; exec stts ingest --speak $wake_arg \"$item/manifest.json\"; ");
            command.append("else sh \"$HOME/.codex/skills/stts/scripts/stts-session.sh\" stop >/dev/null 2>&1 || true; ");
            command.append("exec sh \"$HOME/.codex/skills/stts/scripts/stts-session.sh\" ingest --speak $wake_arg \"$item/manifest.json\"; fi\n");
        }
        return command.toString();
    }

    private String payloadMarkdown() {
        if (textParts.isEmpty()) {
            return "# Shared Text\n\nNo shared text payload was provided.\n";
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Shared Text\n\n");
        if (textTruncated) {
            markdown.append("Preview only. Complete shared text is available at ")
                    .append("`attachments/shared-text-full.txt`.\n\n");
        }
        markdown.append(String.join("\n\n---\n\n", textParts)).append("\n");
        return markdown.toString();
    }

    private void addTextPart(String value) {
        fullTextParts.add(value);
        fullTextChars += value.length();
        if (textChars >= MAX_TEXT_CHARS) {
            textTruncated = true;
            return;
        }
        String text = value;
        int remaining = MAX_TEXT_CHARS - textChars;
        if (text.length() > remaining) {
            text = text.substring(0, remaining)
                    + "\n\n[Codex Bridge preview capped at " + MAX_TEXT_CHARS
                    + " characters. See attachments/shared-text-full.txt for complete shared text.]";
            textTruncated = true;
        }
        textParts.add(text);
        textChars += text.length();
    }

    private void prepareFullTextAttachment() {
        if (fullTextAttachmentPrepared || !textTruncated || fullTextParts.isEmpty()) {
            return;
        }
        fullTextAttachmentPrepared = true;
        String fullText = String.join("\n\n---\n\n", fullTextParts) + "\n";
        byte[] data = fullText.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
        if (encodedBytes + encoded.length() > MAX_TOTAL_ENCODED_BYTES) {
            files.add(SharedFile.metadataOnly(
                    "shared-text-full.txt",
                    "text/plain; charset=utf-8",
                    "android-intent-text",
                    "complete shared text exceeds " + MAX_TOTAL_ENCODED_BYTES
                            + " byte Codex Bridge command limit"));
            return;
        }
        encodedBytes += encoded.length();
        files.add(new SharedFile(
                uniqueFilename("shared-text-full.txt"),
                "text/plain; charset=utf-8",
                "android-intent-text",
                encoded,
                data.length,
                "complete shared text; payload.md is a preview",
                "full_text",
                fullTextChars));
    }

    private String manifestJson(String itemId) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema\": \"ccat-android-share-v1\",\n");
        json.append("  \"item_id\": \"").append(jsonEscape(itemId)).append("\",\n");
        json.append("  \"created_at\": \"")
                .append(jsonEscape(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date())))
                .append("\",\n");
        json.append("  \"payload\": \"payload.md\",\n");
        json.append("  \"attachments\": [\n");
        for (int i = 0; i < files.size(); i++) {
            SharedFile file = files.get(i);
            json.append("    {");
            json.append("\"name\": \"").append(jsonEscape(file.name)).append("\", ");
            json.append("\"mime_type\": \"").append(jsonEscape(file.mimeType)).append("\", ");
            json.append("\"source_uri\": \"").append(jsonEscape(file.sourceUri)).append("\", ");
            json.append("\"copied\": ").append(file.encodedContent.isEmpty() ? "false" : "true").append(", ");
            json.append("\"size_bytes\": ").append(file.sizeBytes).append(", ");
            json.append("\"path\": \"attachments/").append(jsonEscape(file.name)).append("\"");
            if (!file.role.isEmpty()) {
                json.append(", \"role\": \"").append(jsonEscape(file.role)).append("\"");
            }
            if (file.originalCharacterCount > 0) {
                json.append(", \"original_character_count\": ").append(file.originalCharacterCount);
            }
            if (!file.note.isEmpty()) {
                json.append(", \"note\": \"").append(jsonEscape(file.note)).append("\"");
            }
            json.append("}");
            if (i + 1 < files.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private void writeBase64File(StringBuilder command, String path, String content) {
        writeEncodedFile(
                command,
                path,
                Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private void writeEncodedFile(StringBuilder command, String path, String encoded) {
        command.append("printf '%s' ").append(shellSingleQuote(encoded));
        command.append(" | base64 -d > ").append(path).append("\n");
    }

    private String displayNameFor(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String value = cursor.getString(index);
                        if (value != null && !value.isEmpty()) {
                            return value;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "shared-file" : last;
    }

    private static String sanitizeFilename(String value) {
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-").trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "shared-file";
        }
        return cleaned.length() > 96 ? cleaned.substring(0, 96) : cleaned;
    }

    private String uniqueFilename(String name) {
        if (!filenames.contains(name)) {
            filenames.add(name);
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index < 1000; index++) {
            String candidate = stem + "-" + index + extension;
            if (!filenames.contains(candidate)) {
                filenames.add(candidate);
                return candidate;
            }
        }
        String fallback = stem + "-" + System.currentTimeMillis() + extension;
        filenames.add(fallback);
        return fallback;
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class SharedFile {
        final String name;
        final String mimeType;
        final String sourceUri;
        final String encodedContent;
        final int sizeBytes;
        final String note;
        final String role;
        final int originalCharacterCount;

        SharedFile(String name, String mimeType, String sourceUri, String encodedContent, int sizeBytes, String note) {
            this(name, mimeType, sourceUri, encodedContent, sizeBytes, note, "", 0);
        }

        SharedFile(
                String name,
                String mimeType,
                String sourceUri,
                String encodedContent,
                int sizeBytes,
                String note,
                String role,
                int originalCharacterCount) {
            this.name = name;
            this.mimeType = mimeType;
            this.sourceUri = sourceUri;
            this.encodedContent = encodedContent;
            this.sizeBytes = sizeBytes;
            this.note = note;
            this.role = role;
            this.originalCharacterCount = originalCharacterCount;
        }

        static SharedFile metadataOnly(String name, String mimeType, String sourceUri, String note) {
            return new SharedFile(name, mimeType, sourceUri, "", 0, note);
        }
    }
}
