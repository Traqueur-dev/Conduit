package fr.traqueur.conduit.compression;

/**
 * No-operation compressor that passes data through unchanged.
 * Useful when compression is not needed or for testing.
 *
 * @author Traqueur
 */
public class NoOpCompressor implements Compressor {

    /**
     * Creates a new no-op compressor.
     */
    public NoOpCompressor() {
    }
    
    @Override
    public byte[] compress(byte[] data) {
        return data;
    }
    
    @Override
    public byte[] decompress(byte[] data) {
        return data;
    }
    
    @Override
    public String getType() {
        return "none";
    }
}