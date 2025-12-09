package fr.traqueur.conduit.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gzip compression implementation.
 * Provides good compression ratio with reasonable performance.
 *
 * @author Traqueur
 */
public class GzipCompressor implements Compressor {

    /**
     * Creates a new Gzip compressor.
     */
    public GzipCompressor() {
    }
    
    @Override
    public byte[] compress(byte[] data) throws Exception {
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream(data.length);
             GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
            
            gzipStream.write(data);
            gzipStream.finish();
            return byteStream.toByteArray();
        }
    }
    
    @Override
    public byte[] decompress(byte[] data) throws Exception {
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
             GZIPInputStream gzipStream = new GZIPInputStream(byteStream);
             ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipStream.read(buffer)) > 0) {
                outStream.write(buffer, 0, len);
            }
            return outStream.toByteArray();
        }
    }
    
    @Override
    public String getType() {
        return "gzip";
    }
}