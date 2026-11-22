package ai.onezion.phoneclip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

final class Media {
    static final class Photo {
        Uri uri;
        long dateAdded;
        String name;
    }

    static Photo newestPhoto(Context ctx, long minDateAdded, boolean allAlbums) {
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
        };
        String selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
        String[] args = { String.valueOf(minDateAdded) };
        if (!allAlbums) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.Images.Media.DATE_ADDED + " > ?";
            args = new String[] { "DCIM/Camera%", String.valueOf(minDateAdded) };
        }
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (android.database.Cursor c = ctx.getContentResolver().query(
                base, projection, selection, args, sort)) {
            if (c == null || !c.moveToFirst()) return null;
            Photo p = new Photo();
            p.uri = Uri.withAppendedPath(base, String.valueOf(c.getLong(0)));
            p.dateAdded = c.getLong(1);
            p.name = c.getString(2);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] jpegFromUri(Context ctx, Uri uri, int maxEdge, int quality) throws Exception {
        return jpeg(ImageDecoder.createSource(ctx.getContentResolver(), uri), maxEdge, quality);
    }

    static byte[] jpegFromFile(File file, int maxEdge, int quality) throws Exception {
        return jpeg(ImageDecoder.createSource(file), maxEdge, quality);
    }

    private static byte[] jpeg(ImageDecoder.Source source, int maxEdge, int quality) throws Exception {
        Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            float scale = Math.min(1f, maxEdge / (float) Math.max(w, h));
            if (scale < 1f) {
                decoder.setTargetSize(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    /** Remux the first audio track into an .m4a file. No re-encoding, so it is fast. */
    static File extractAudio(Context ctx, Uri uri, String baseName) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        File out = null;
        MediaMuxer mux = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) throw new Exception("没有音轨");
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.contains("mp4a")) {
                throw new Exception("音轨格式 " + mime + " 暂不支持直接抽取");
            }

            // an empty audio track makes MediaMuxer.stop() throw; fail with a real reason
            ex.selectTrack(track);
            ByteBuffer probe = ByteBuffer.allocateDirect(1 << 16);
            if (ex.readSampleData(probe, 0) < 0) throw new Exception("音轨没有数据");
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            File dir = new File(ctx.getFilesDir(), "derived");
            dir.mkdirs();
            out = new File(dir, baseName + ".m4a");
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = mux.addTrack(format);
            mux.start();

            ByteBuffer buf = ByteBuffer.allocateDirect(4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = ex.getSampleTime();
                // extractor sample flags are not muxer flags: 2/4 mean encrypted/partial
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(dst, buf, info);
                ex.advance();
            }
            mux.stop();
            return out;
        } finally {
            if (mux != null) mux.release();
            ex.release();
        }
    }

    static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(new String(data).hashCode());
        }
    }
}
