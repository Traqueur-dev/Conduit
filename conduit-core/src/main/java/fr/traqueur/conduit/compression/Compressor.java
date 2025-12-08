package fr.traqueur.conduit.compression;

/**
 * Abstraction for data compression strategies (Gzip, LZ4, None, etc.).
 *
 * @author Traqueur
 */
public interface Compressor {
    
    /**
     * Compresses data.
     * 
     * @param data the raw data to compress
     * @return compressed data
     * @throws Exception if compression fails
     */
    byte[] compress(byte[] data) throws Exception;
    
    /**
     * Decompresses data.
     * 
     * @param data the compressed data
     * @return decompressed data
     * @throws Exception if decompression fails
     */
    byte[] decompress(byte[] data) throws Exception;
    
    /**
     * Gets the compressor type identifier.
     * 
     * @return compressor type (e.g., "gzip", "none")
     */
    String getType();
}