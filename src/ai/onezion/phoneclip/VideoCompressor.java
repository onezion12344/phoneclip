package ai.onezion.phoneclip;

import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Re-encodes a video to 720p H.264 and copies the original audio track.
 * Uses decoded YUV Images as encoder input: the surface path produced no frames
 * on the Qualcomm Codec2 stack (encoder stayed empty until EOS).
 */
final class VideoCompressor {
    private static final int MAX_EDGE = 1280;
    private static final int BITRATE = 2_500_000;
    private static final float FPS = 30f;

    static File compress(Context ctx, Uri uri, String baseName) throws Exception {
        MediaExtractor video = new MediaExtractor();
        video.setDataSource(ctx, uri, null);
        MediaExtractor audio = new MediaExtractor();
        audio.setDataSource(ctx, uri, null);

        MediaMuxer mux = null;
        MediaCodec dec = null;
        MediaCodec enc = null;
        try {
            int vTrack = -1;
            MediaFormat source = null;
            for (int i = 0; i < video.getTrackCount(); i++) {
                MediaFormat f = video.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    vTrack = i;
                    source = f;
                    break;
                }
            }
            if (vTrack < 0 || source == null) throw new Exception("没有视频轨");
            video.selectTrack(vTrack);

            int aTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < audio.getTrackCount(); i++) {
                MediaFormat f = audio.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    aTrack = i;
                    audioFormat = f;
                    break;
                }
            }
            boolean keepAudio = audioFormat != null
                    && audioFormat.getString(MediaFormat.KEY_MIME) != null
                    && audioFormat.getString(MediaFormat.KEY_MIME).contains("mp4a");
            if (keepAudio) {
                audio.selectTrack(aTrack);
                ByteBuffer probe = ByteBuffer.allocateDirect(1 << 16);
                if (audio.readSampleData(probe, 0) < 0) keepAudio = false;
                audio.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }

            int w = source.getInteger(MediaFormat.KEY_WIDTH);
            int h = source.getInteger(MediaFormat.KEY_HEIGHT);
            double scale = Math.max(w, h) > MAX_EDGE ? MAX_EDGE / (double) Math.max(w, h) : 1.0;
            int outW = even((int) Math.round(w * scale));
            int outH = even((int) Math.round(h * scale));

            int rotation = 0;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(ctx, uri);
                String r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (r != null) rotation = Integer.parseInt(r);
            } catch (Exception ignored) {
            } finally {
                mmr.release();
            }

            File dir = new File(ctx.getFilesDir(), "derived");
            dir.mkdirs();
            File out = new File(dir, baseName + "-720p.mp4");
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) mux.setOrientationHint(rotation);

            enc = MediaCodec.createEncoderByType("video/avc");
            MediaFormat target = MediaFormat.createVideoFormat("video/avc", outW, outH);
            target.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            target.setInteger(MediaFormat.KEY_FRAME_RATE, (int) FPS);
            target.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            target.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            enc.configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            dec = MediaCodec.createDecoderByType(source.getString(MediaFormat.KEY_MIME));
            MediaFormat decFmt = new MediaFormat(source);
            decFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            dec.configure(decFmt, null, null, 0);

            enc.start();
            dec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer sample = ByteBuffer.allocateDirect(4 << 20);
            long startedAt = System.currentTimeMillis();
            boolean inputDone = false, decoderDone = false, encoderDone = false;
            boolean muxStarted = false;
            int videoMuxTrack = -1;
            int audioMuxTrack = -1;
            long lastOutput = System.currentTimeMillis();
            int inCount = 0, encOutCount = 0;

            while (!encoderDone) {
                if (!inputDone) {
                    int in = dec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(in);
                        long need = video.getSampleSize();
                        if (need > sample.capacity()) {
                            if (need > (16L << 20)) throw new Exception("样本过大：" + need);
                            sample = ByteBuffer.allocateDirect((int) need);
                        }
                        int size = buf == null ? -1 : video.readSampleData(sample, 0);
                        if (size >= 0 && buf != null) {
                            if (size > buf.capacity()) {
                                throw new Exception("样本大于解码器输入缓冲");
                            }
                            sample.position(0);
                            sample.limit(size);
                            buf.clear();
                            buf.put(sample);
                        }
                        if (size < 0) {
                            dec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(in, 0, size, video.getSampleTime(),
                                    video.getSampleFlags());
                            video.advance();
                            inCount++;
                        }
                    }
                }

                if (!decoderDone) {
                    int outIdx = dec.dequeueOutputBuffer(info, 0);
                    if (outIdx >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (!eos) {
                            int encIn = enc.dequeueInputBuffer(10_000);
                            if (encIn >= 0) {
                                Image src = dec.getOutputImage(outIdx);
                                Image dst = enc.getInputImage(encIn);
                                int chromaW = Math.max(1, outW / 2);
                                int chromaH = Math.max(1, outH / 2);
                                copyPlane(src.getPlanes()[0], dst.getPlanes()[0], outW, outH);
                                copyPlane(src.getPlanes()[1], dst.getPlanes()[1], chromaW, chromaH);
                                copyPlane(src.getPlanes()[2], dst.getPlanes()[2], chromaW, chromaH);
                                enc.queueInputBuffer(encIn, 0, outW * outH * 3 / 2,
                                        info.presentationTimeUs, 0);
                                dec.releaseOutputBuffer(outIdx, false);
                            } else {
                                dec.releaseOutputBuffer(outIdx, false);
                            }
                        } else {
                            dec.releaseOutputBuffer(outIdx, false);
                            decoderDone = true;
                            int encIn = enc.dequeueInputBuffer(10_000);
                            if (encIn >= 0) {
                                enc.queueInputBuffer(encIn, 0, 0, info.presentationTimeUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                    }
                }

                int outIdx = enc.dequeueOutputBuffer(info, 10_000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new Exception("encoder format changed twice");
                    videoMuxTrack = mux.addTrack(enc.getOutputFormat());
                    if (keepAudio) {
                        try {
                            audioMuxTrack = mux.addTrack(audioFormat);
                        } catch (Exception e) {
                            keepAudio = false;
                        }
                    }
                    mux.start();
                    muxStarted = true;
                    lastOutput = System.currentTimeMillis();
                } else if (outIdx >= 0) {
                    ByteBuffer buf = enc.getOutputBuffer(outIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && buf != null && muxStarted) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        mux.writeSampleData(videoMuxTrack, buf, info);
                    }
                    enc.releaseOutputBuffer(outIdx, false);
                    encOutCount++;
                    lastOutput = System.currentTimeMillis();
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
                if (System.currentTimeMillis() - lastOutput > 30000
                        || System.currentTimeMillis() - startedAt > 30L * 60 * 1000) {
                    throw new Exception("编码器无输出或超时，已中止");
                }
            }

            if (keepAudio && muxStarted && audioMuxTrack >= 0) {
                audio.selectTrack(aTrack);
                ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
                MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                while (true) {
                    int size = audio.readSampleData(buf, 0);
                    if (size < 0) break;
                    ai.offset = 0;
                    ai.size = size;
                    ai.presentationTimeUs = audio.getSampleTime();
                    ai.flags = audio.getSampleFlags();
                    mux.writeSampleData(audioMuxTrack, buf, ai);
                    audio.advance();
                }
            }

            android.util.Log.i("PhoneClip", "compress: in=" + inCount
                    + " encOut=" + encOutCount + " ms=" + (System.currentTimeMillis() - startedAt));
            if (muxStarted) mux.stop();
            return out;
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) {}
                dec.release();
            }
            if (enc != null) {
                try { enc.stop(); } catch (Exception ignored) {}
                enc.release();
            }
            if (mux != null) mux.release();
            video.release();
            audio.release();
        }
    }

    private static void copyPlane(Image.Plane src, Image.Plane dst, int w, int h) {
        ByteBuffer s = src.getBuffer();
        ByteBuffer d = dst.getBuffer();
        int srs = src.getRowStride(), sps = src.getPixelStride();
        int drs = dst.getRowStride(), dps = dst.getPixelStride();
        for (int y = 0; y < h; y++) {
            int sBase = y * srs, dBase = y * drs;
            for (int x = 0; x < w; x++) {
                d.put(dBase + x * dps, s.get(sBase + x * sps));
            }
        }
    }

    private static int even(int v) {
        return v % 2 == 0 ? v : v - 1;
    }
}
