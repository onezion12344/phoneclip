package ai.onezion.phoneclip;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;

/** Re-encodes the first audio track to AAC-LC 96 kbps in an .m4a container. */
final class AudioCompressor {
    private static final int BITRATE = 96_000;

    static File compress(Context ctx, Uri uri, String baseName) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, uri, null);
        MediaCodec dec = null;
        MediaCodec enc = null;
        MediaMuxer mux = null;
        try {
            int track = -1;
            MediaFormat source = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    source = f;
                    break;
                }
            }
            if (track < 0 || source == null) throw new Exception("没有音轨");
            ex.selectTrack(track);

            int rate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = source.getString(MediaFormat.KEY_MIME);

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(source, null, null, 0);
            dec.start();

            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            MediaFormat target = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels);
            target.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            target.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            target.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc.configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            File dir = new File(ctx.getFilesDir(), "derived");
            dir.mkdirs();
            File out = new File(dir, baseName + "-96k.m4a");
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false, encDone = false, encoderEosSent = false;
            boolean pendingEos = false;
            int pendingDecOut = -1;
            int muxTrack = -1;
            boolean muxStarted = false;
            long lastProgress = System.currentTimeMillis();

            while (!encDone) {
                if (!inputDone) {
                    int in = dec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(in);
                        int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                if (pendingDecOut < 0) {
                    int idx = dec.dequeueOutputBuffer(decInfo, 0);
                    if (idx >= 0) pendingDecOut = idx;
                }

                if (pendingDecOut >= 0) {
                    int encIn = enc.dequeueInputBuffer(0);
                    if (encIn >= 0) {
                        ByteBuffer src = dec.getOutputBuffer(pendingDecOut);
                        ByteBuffer dst = enc.getInputBuffer(encIn);
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (src != null && dst != null && decInfo.size > 0) {
                            src.position(decInfo.offset);
                            src.limit(decInfo.offset + decInfo.size);
                            dst.clear();
                            dst.put(src);
                            enc.queueInputBuffer(encIn, 0, decInfo.size,
                                    decInfo.presentationTimeUs, 0);
                            if (eos) pendingEos = true;
                        } else if (eos) {
                            enc.queueInputBuffer(encIn, 0, 0, decInfo.presentationTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encoderEosSent = true;
                        } else {
                            enc.queueInputBuffer(encIn, 0, 0, decInfo.presentationTimeUs, 0);
                        }
                        dec.releaseOutputBuffer(pendingDecOut, false);
                        pendingDecOut = -1;
                    }
                }

                // the decoder's last buffer often carries data *and* EOS; the EOS must
                // follow it as a separate empty buffer, and the decoder will not hand
                // out another output, so this cannot live inside the block above.
                if (pendingEos && !encoderEosSent) {
                    int encIn = enc.dequeueInputBuffer(0);
                    if (encIn >= 0) {
                        enc.queueInputBuffer(encIn, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        pendingEos = false;
                        encoderEosSent = true;
                    }
                }

                int idx = enc.dequeueOutputBuffer(encInfo, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new Exception("encoder format changed twice");
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                    lastProgress = System.currentTimeMillis();
                } else if (idx >= 0) {
                    ByteBuffer buf = enc.getOutputBuffer(idx);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encInfo.size = 0;
                    }
                    if (encInfo.size > 0 && buf != null && muxStarted) {
                        buf.position(encInfo.offset);
                        buf.limit(encInfo.offset + encInfo.size);
                        mux.writeSampleData(muxTrack, buf, encInfo);
                    }
                    enc.releaseOutputBuffer(idx, false);
                    lastProgress = System.currentTimeMillis();
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encDone = true;
                    }
                }

                if (inputDone && pendingDecOut < 0 && !encoderEosSent
                        && System.currentTimeMillis() - lastProgress > 20_000) {
                    throw new Exception("音频编码无输出，已中止");
                }
            }

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
            ex.release();
        }
    }
}
