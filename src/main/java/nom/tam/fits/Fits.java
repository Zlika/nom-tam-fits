package nom.tam.fits;

/*
 * #%L
 * nom.tam FITS library
 * %%
 * Copyright (C) 2004 - 2015 nom-tam-fits
 * %%
 * This is free and unencumbered software released into the public domain.
 * 
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * #L%
 */

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import nom.tam.fits.compress.CompressionManager;
import nom.tam.fits.header.Checksum;
import nom.tam.util.ArrayDataInput;
import nom.tam.util.ArrayDataOutput;
import nom.tam.util.AsciiFuncs;
import nom.tam.util.BufferedDataInputStream;
import nom.tam.util.BufferedDataOutputStream;
import nom.tam.util.BufferedFile;
import nom.tam.util.RandomAccess;

/**
 * This class provides access to routines to allow users to read and write FITS
 * files.
 * <p>
 * <p>
 * <b> Description of the Package </b>
 * <p>
 * This FITS package attempts to make using FITS files easy, but does not do
 * exhaustive error checking. Users should not assume that just because a FITS
 * file can be read and written that it is necessarily legal FITS. These classes
 * try to make it easy to transform between arrays of Java primitives and their
 * FITS encodings.
 * <ul>
 * <li>The Fits class provides capabilities to read and write data at the HDU
 * level, and to add and delete HDU's from the current Fits object. A large
 * number of constructors are provided which allow users to associate the Fits
 * object with some form of external data. This external data may be in a
 * compressed format.
 * <li>The HDU class is a factory class which is used to create HDUs. HDU's can
 * be of a number of types derived from the abstract class BasicHDU. The
 * hierarchy of HDUs is:
 * <ul>
 * <li>BasicHDU
 * <ul>
 * <li>ImageHDU
 * <li>RandomGroupsHDU
 * <li>TableHDU
 * <ul>
 * <li>BinaryTableHDU
 * <li>AsciiTableHDU
 * </ul>
 * </ul>
 * </ul>
 * <li>The Header class provides many functions to add, delete and read header
 * keywords in a variety of formats.
 * <li>The HeaderCard class provides access to the structure of a FITS header
 * card.
 * <li>The Data class is an abstract class which provides the basic methods for
 * reading and writing FITS data. Users will likely only be interested in the
 * getData method which returns that actual FITS data.
 * <li>The TableHDU class provides a large number of methods to access and
 * modify information in tables.
 * <li>The Column class combines the Header information and Data corresponding
 * to a given column.
 * </ul>
 * 
 * @version 1.11
 */
public class Fits implements Closeable {

    private static Logger LOG = Logger.getLogger(Fits.class.getName());

    /**
     * Calculate the Seaman-Pence 32-bit 1's complement checksum over the byte
     * stream. The option to start from an intermediate checksum accumulated
     * over another previous byte stream is not implemented. The implementation
     * accumulates in two 64-bit integer values the two low-order and the two
     * high-order bytes of adjacent 4-byte groups. A carry-over of bits is never
     * done within the main loop (only once at the end at reduction to a 32-bit
     * positive integer) since an overflow of a 64-bit value (signed, with
     * maximum at 2^63-1) by summation of 16-bit values could only occur after
     * adding approximately 140G short values (=2^47) (280GBytes) or more. We
     * assume for now that this routine here is never called to swallow FITS
     * files of that size or larger. by R J Mathar
     * 
     * @param data
     *            the byte sequence
     * @return the 32bit checksum in the range from 0 to 2^32-1
     * @see Checksum#CHECKSUM
     * @since 2005-10-05
     */
    public static long checksum(final byte[] data) {
        long hi = 0;
        long lo = 0;
        final int len = 2 * (data.length / 4);
        // System.out.println(data.length + " bytes") ;
        final int remain = data.length % 4;
        /*
         * a write(2) on Sparc/PA-RISC would write the MSB first, on Linux the
         * LSB; by some kind of coincidence, we can stay with the byte order
         * known from the original C version of the algorithm.
         */
        for (int i = 0; i < len; i += 2) {
            /*
             * The four bytes in this block handled by a single 'i' are each
             * signed (-128 to 127) in Java and need to be masked indivdually to
             * avoid sign extension /propagation.
             */
            hi += data[2 * i] << 8 & 0xff00L | data[2 * i + 1] & 0xffL;
            lo += data[2 * i + 2] << 8 & 0xff00L | data[2 * i + 3] & 0xffL;
        }

        /*
         * The following three cases actually cannot happen since FITS records
         * are multiples of 2880 bytes.
         */
        if (remain >= 1) {
            hi += data[2 * len] << 8 & 0xff00L;
        }
        if (remain >= 2) {
            hi += data[2 * len + 1] & 0xffL;
        }
        if (remain >= 3) {
            lo += data[2 * len + 2] << 8 & 0xff00L;
        }

        long hicarry = hi >>> 16;
        long locarry = lo >>> 16;
        while (hicarry != 0 || locarry != 0) {
            hi = (hi & 0xffffL) + locarry;
            lo = (lo & 0xffffL) + hicarry;
            hicarry = hi >>> 16;
            locarry = lo >>> 16;
        }
        return (hi << 16) + lo;
    }

    /**
     * Encode a 32bit integer according to the Seaman-Pence proposal.
     * 
     * @param c
     *            the checksum previously calculated
     * @return the encoded string of 16 bytes.
     * @see http
     *      ://heasarc.gsfc.nasa.gov/docs/heasarc/ofwg/docs/general/checksum/
     *      node14.html#SECTION00035000000000000000
     * @author R J Mathar
     * @since 2005-10-05
     */
    private static String checksumEnc(final long c, final boolean compl) {
        byte[] asc = new byte[16];
        final int[] exclude = {
            0x3a,
            0x3b,
            0x3c,
            0x3d,
            0x3e,
            0x3f,
            0x40,
            0x5b,
            0x5c,
            0x5d,
            0x5e,
            0x5f,
            0x60
        };
        final long[] mask = {
            0xff000000L,
            0xff0000L,
            0xff00L,
            0xffL
        };
        final int offset = 0x30; /* ASCII 0 (zero */
        final long value = compl ? ~c : c;
        for (int i = 0; i < 4; i++) {
            final int byt = (int) ((value & mask[i]) >>> 24 - 8 * i); // each
                                                                      // byte
                                                                      // becomes
                                                                      // four
            final int quotient = byt / 4 + offset;
            final int remainder = byt % 4;
            int[] ch = new int[4];
            for (int j = 0; j < 4; j++) {
                ch[j] = quotient;
            }

            ch[0] += remainder;
            boolean check = true;
            for (; check;) // avoid ASCII punctuation
            {
                check = false;
                for (int element : exclude) {
                    for (int j = 0; j < 4; j += 2) {
                        if (ch[j] == element || ch[j + 1] == element) {
                            ch[j]++;
                            ch[j + 1]--;
                            check = true;
                        }
                    }
                }
            }

            for (int j = 0; j < 4; j++) // assign the bytes
            {
                asc[4 * j + i] = (byte) ch[j];
            }
        }
        // shift the bytes 1 to the right circularly.
        String resul = AsciiFuncs.asciiString(asc, 15, 1);
        return resul.concat(AsciiFuncs.asciiString(asc, 0, 15));
    }

    /**
     * Create an HDU from the given Data.
     * 
     * @param data
     *            The data to be described in this HDU.
     */
    public static <DataClass extends Data> BasicHDU<DataClass> makeHDU(DataClass data) throws FitsException {
        Header hdr = new Header();
        data.fillHeader(hdr);
        return FitsFactory.HDUFactory(hdr, data);
    }

    /**
     * Create an HDU from the given header.
     * 
     * @param h
     *            The header which describes the FITS extension
     */
    public static BasicHDU<?> makeHDU(Header h) throws FitsException {
        Data d = FitsFactory.dataFactory(h);
        return FitsFactory.HDUFactory(h, d);
    }

    /**
     * Create an HDU from the given data kernel.
     * 
     * @param o
     *            The data to be described in this HDU.
     */
    public static BasicHDU<?> makeHDU(Object o) throws FitsException {
        return FitsFactory.HDUFactory(o);
    }

    /**
     * Add or update the CHECKSUM keyword. by R J Mathar
     * 
     * @param hdu
     *            the HDU to be updated.
     * @throws nom.tam.fits.HeaderCardException
     * @since 2005-10-05
     */
    public static void setChecksum(BasicHDU<?> hdu) throws nom.tam.fits.HeaderCardException, nom.tam.fits.FitsException, java.io.IOException {
        /*
         * the next line with the delete is needed to avoid some unexpected
         * problems with non.tam.fits.Header.checkCard() which otherwise says it
         * expected PCOUNT and found DATE.
         */
        Header hdr = hdu.getHeader();
        hdr.deleteKey("CHECKSUM");
        /*
         * jThis would need org.nevec.utils.DateUtils compiled before
         * org.nevec.prima.fits .... final String doneAt =
         * DateUtils.dateToISOstring(0) ; We need to save the value of the
         * comment string because this is becoming part of the checksum
         * calculated and needs to be re-inserted again - with the same string -
         * when the second/final call to addValue() is made below.
         */
        final String doneAt = HeaderCommentsMap.getComment("fits:checksum:1");
        hdr.addValue("CHECKSUM", "0000000000000000", doneAt);

        /*
         * Convert the entire sequence of 2880 byte header cards into a byte
         * array. The main benefit compared to the C implementations is that we
         * do not need to worry about the particular byte order on machines
         * (Linux/VAX/MIPS vs Hp-UX, Sparc...) supposed that the correct
         * implementation is in the write() interface.
         */
        ByteArrayOutputStream hduByteImage = new ByteArrayOutputStream();
        BufferedDataOutputStream bdos = new BufferedDataOutputStream(hduByteImage);

        // DATASUM keyword.
        hdu.getData().write(bdos);
        bdos.flush();
        byte[] data = hduByteImage.toByteArray();
        checksum(data);
        hdu.write(new BufferedDataOutputStream(hduByteImage));
        long csd = checksum(data);
        hdu.getHeader().addValue("DATASUM", "" + csd, "Checksum of data");

        // We already have the checsum of the data. Lets compute it for
        // the header.
        hduByteImage.reset();
        hdu.getHeader().write(bdos);
        bdos.flush();
        data = hduByteImage.toByteArray();

        long csh = checksum(data);

        long cshdu = csh + csd;
        // If we had a carry it should go into the
        // beginning.
        while ((cshdu & 0xFFFFFFFF00000000L) != 0) {
            cshdu = (cshdu & 0xFFFFFFFFL) + 1;
        }
        /*
         * This time we do not use a deleteKey() to ensure that the keyword is
         * replaced "in place". Note that the value of the checksum is actually
         * independent to a permutation of the 80-byte records within the
         * header.
         */
        hdr.addValue("CHECKSUM", checksumEnc(cshdu, true), doneAt);
    }

    /** Indicate the version of these classes */
    public static String version() {
        Properties props = new Properties();
        try {
            props.load(Fits.class.getResourceAsStream("/META-INF/maven/gov.nasa.gsfc.heasarc/nom-tam-fits/pom.properties"));
            return props.getProperty("version");
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * The input stream associated with this Fits object.
     */
    private ArrayDataInput dataStr;

    /**
     * A vector of HDUs that have been added to this Fits object.
     */
    private final List<BasicHDU<?>> hduList = new ArrayList<>();

    /**
     * Has the input stream reached the EOF?
     */
    private boolean atEOF;

    /**
     * The last offset we reached. A -1 is used to indicate that we cannot use
     * the offset.
     */
    private long lastFileOffset = -1;

    /**
     * Create an empty Fits object which is not associated with an input stream.
     */
    public Fits() {
    }

    /**
     * Associate FITS object with a File. If the file is compressed a stream
     * will be used, otherwise random access will be supported.
     * 
     * @param myFile
     *            The File object.
     */
    public Fits(File myFile) throws FitsException {
        this(myFile, CompressionManager.isCompressed(myFile));
    }

    /**
     * Associate the Fits object with a File
     * 
     * @param myFile
     *            The File object.
     * @param compressed
     *            Is the data compressed?
     */
    public Fits(File myFile, boolean compressed) throws FitsException {
        fileInit(myFile, compressed);
    }

    /**
     * Create a Fits object associated with the given data stream. Compression
     * is determined from the first few bytes of the stream.
     * 
     * @param str
     *            The data stream.
     */
    public Fits(InputStream str) throws FitsException {
        streamInit(str, false);
    }

    /**
     * Create a Fits object associated with a data stream.
     * 
     * @param str
     *            The data stream.
     * @param compressed
     *            Is the stream compressed? This is currently ignored.
     *            Compression is determined from the first two bytes in the
     *            stream.
     */
    public Fits(InputStream str, boolean compressed) throws FitsException {
        streamInit(str);
    }

    /**
     * Associate the FITS object with a file or URL. The string is assumed to be
     * a URL if it begins one of the protocol strings. If the string ends in .gz
     * it is assumed that the data is in a compressed format. All string
     * comparisons are case insensitive.
     * 
     * @param filename
     *            The name of the file or URL to be processed.
     * @exception FitsException
     *                Thrown if unable to find or open a file or URL from the
     *                string given.
     **/
    public Fits(String filename) throws FitsException {
        this(filename, CompressionManager.isCompressed(filename));
    }

    /**
     * Associate the FITS object with a file or URL. The string is assumed to be
     * a URL if it begins one of the protocol strings. If the string ends in .gz
     * it is assumed that the data is in a compressed format. All string
     * comparisons are case insensitive.
     * 
     * @param filename
     *            The name of the file or URL to be processed.
     * @exception FitsException
     *                Thrown if unable to find or open a file or URL from the
     *                string given.
     **/
    public Fits(String filename, boolean compressed) throws FitsException {
        if (filename == null) {
            throw new FitsException("Null FITS Identifier String");
        }
        try {
            File fil = new File(filename);
            if (fil.exists()) {
                fileInit(fil, compressed);
                return;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "not a file " + filename, e);
        }
        try {
            InputStream str = Thread.currentThread().getContextClassLoader().getResourceAsStream(filename);
            if (str != null) {
                streamInit(str);
                return;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "not a resource " + filename, e);
        }
        try {
            InputStream is = FitsUtil.getURLStream(new URL(filename), 0);
            streamInit(is);
            return;
        } catch (Exception e) {
            LOG.log(Level.FINE, "not a url " + filename, e);
        }
        throw new FitsException("could not detect type of " + filename);
    }

    /**
     * Associate the FITS object with a given URL
     * 
     * @param myURL
     * @exception FitsException
     *                Thrown if unable to find or open a file or URL from the
     *                string given.
     */
    public Fits(URL myURL) throws FitsException {
        try {
            streamInit(FitsUtil.getURLStream(myURL, 0));
        } catch (IOException e) {
            throw new FitsException("Unable to open input from URL:" + myURL);
        }
    }

    /**
     * Associate the FITS object with a given uncompressed URL
     * 
     * @param myURL
     *            The URL to be associated with the FITS file.
     * @param compressed
     *            Compression flag, ignored.
     * @exception FitsException
     *                Thrown if unable to use the specified URL.
     */
    public Fits(URL myURL, boolean compressed) throws FitsException {
        this(myURL);
    }

    /**
     * Add an HDU to the Fits object. Users may intermix calls to functions
     * which read HDUs from an associated input stream with the addHDU and
     * insertHDU calls, but should be careful to understand the consequences.
     * 
     * @param myHDU
     *            The HDU to be added to the end of the FITS object.
     */
    public void addHDU(BasicHDU<?> myHDU) throws FitsException {
        insertHDU(myHDU, getNumberOfHDUs());
    }

    /**
     * Get the current number of HDUs in the Fits object.
     * 
     * @return The number of HDU's in the object.
     * @deprecated See getNumberOfHDUs()
     */
    @Deprecated
    public int currentSize() {
        return this.hduList.size();
    }

    /**
     * Delete an HDU from the HDU list.
     * 
     * @param n
     *            The index of the HDU to be deleted. If n is 0 and there is
     *            more than one HDU present, then the next HDU will be converted
     *            from an image to primary HDU if possible. If not a dummy
     *            header HDU will then be inserted.
     */
    public void deleteHDU(int n) throws FitsException {
        int size = getNumberOfHDUs();
        if (n < 0 || n >= size) {
            throw new FitsException("Attempt to delete non-existent HDU:" + n);
        }
        this.hduList.remove(n);
        if (n == 0 && size > 1) {
            BasicHDU<?> newFirst = this.hduList.get(0);
            if (newFirst.canBePrimary()) {
                newFirst.setPrimaryHDU(true);
            } else {
                insertHDU(BasicHDU.getDummyHDU(), 0);
            }
        }
    }

    /**
     * Get a stream from the file and then use the stream initialization.
     * 
     * @param myFile
     *            The File to be associated.
     * @param compressed
     *            Is the data compressed?
     */
    protected void fileInit(File myFile, boolean compressed) throws FitsException {
        try {
            if (compressed) {
                streamInit(new FileInputStream(myFile));
            } else {
                randomInit(myFile);
            }
        } catch (IOException e) {
            throw new FitsException("Unable to create Input Stream from File: " + myFile);
        }
    }

    /**
     * Return the n'th HDU. If the HDU is already read simply return a pointer
     * to the cached data. Otherwise read the associated stream until the n'th
     * HDU is read.
     * 
     * @param n
     *            The index of the HDU to be read. The primary HDU is index 0.
     * @return The n'th HDU or null if it could not be found.
     */
    public BasicHDU<?> getHDU(int n) throws FitsException, IOException {
        int size = getNumberOfHDUs();
        for (int i = size; i <= n; i += 1) {
            BasicHDU<?> hdu = readHDU();
            if (hdu == null) {
                return null;
            }
        }
        return this.hduList.get(n);
    }

    /**
     * Get the current number of HDUs in the Fits object.
     * 
     * @return The number of HDU's in the object.
     */
    public int getNumberOfHDUs() {
        return this.hduList.size();
    }

    /**
     * Get the data stream used for the Fits Data.
     * 
     * @return The associated data stream. Users may wish to call this function
     *         after opening a Fits object when they wish detailed control for
     *         writing some part of the FITS file.
     */
    public ArrayDataInput getStream() {
        return this.dataStr;
    }

    /**
     * Insert a FITS object into the list of HDUs.
     * 
     * @param myHDU
     *            The HDU to be inserted into the list of HDUs.
     * @param n
     *            The location at which the HDU is to be inserted.
     */
    public void insertHDU(BasicHDU<?> myHDU, int n) throws FitsException {
        if (myHDU == null) {
            return;
        }
        if (n < 0 || n > getNumberOfHDUs()) {
            throw new FitsException("Attempt to insert HDU at invalid location: " + n);
        }
        try {
            if (n == 0) {
                // Note that the previous initial HDU is no longer the first.
                // If we were to insert tables backwards from last to first,
                // we could get a lot of extraneous DummyHDUs but we currently
                // do not worry about that.
                if (getNumberOfHDUs() > 0) {
                    this.hduList.get(0).setPrimaryHDU(false);
                }
                if (myHDU.canBePrimary()) {
                    myHDU.setPrimaryHDU(true);
                    this.hduList.add(0, myHDU);
                } else {
                    insertHDU(BasicHDU.getDummyHDU(), 0);
                    myHDU.setPrimaryHDU(false);
                    this.hduList.add(1, myHDU);
                }
            } else {
                myHDU.setPrimaryHDU(false);
                this.hduList.add(n, myHDU);
            }
        } catch (NoSuchElementException e) {
            throw new FitsException("hduList inconsistency in insertHDU");
        }
    }

    /**
     * Initialize using buffered random access. This implies that the data is
     * uncompressed.
     * 
     * @param f
     * @throws FitsException
     */
    protected void randomInit(File f) throws FitsException {

        String permissions = "r";
        if (!f.exists() || !f.canRead()) {
            throw new FitsException("Non-existent or unreadable file");
        }
        if (f.canWrite()) {
            permissions += "w";
        }
        try {
            this.dataStr = new BufferedFile(f, permissions);
            ((BufferedFile) this.dataStr).seek(0);
        } catch (IOException e) {
            throw new FitsException("Unable to open file " + f.getPath());
        }
    }

    /**
     * Return all HDUs for the Fits object. If the FITS file is associated with
     * an external stream make sure that we have exhausted the stream.
     * 
     * @return an array of all HDUs in the Fits object. Returns null if there
     *         are no HDUs associated with this object.
     */
    public BasicHDU<?>[] read() throws FitsException {
        readToEnd();
        int size = getNumberOfHDUs();
        if (size == 0) {
            return null;
        }
        return this.hduList.toArray(new BasicHDU<?>[size]);
    }

    /**
     * Read a FITS file from an InputStream object.
     * 
     * @param is
     *            The InputStream stream whence the FITS information is found.
     */
    public void read(InputStream is) throws FitsException, IOException {
        if (is instanceof ArrayDataInput) {
            this.dataStr = (ArrayDataInput) is;
        } else {
            this.dataStr = new BufferedDataInputStream(is);
        }
        read();
    }

    /**
     * Read the next HDU on the default input stream.
     * 
     * @return The HDU read, or null if an EOF was detected. Note that null is
     *         only returned when the EOF is detected immediately at the
     *         beginning of reading the HDU.
     */
    public BasicHDU<?> readHDU() throws FitsException, IOException {
        if (this.dataStr == null || this.atEOF) {
            return null;
        }
        if (this.dataStr instanceof nom.tam.util.RandomAccess && this.lastFileOffset > 0) {
            FitsUtil.reposition(this.dataStr, this.lastFileOffset);
        }
        Header hdr = Header.readHeader(this.dataStr);
        if (hdr == null) {
            this.atEOF = true;
            return null;
        }
        Data data = hdr.makeData();
        try {
            data.read(this.dataStr);
        } catch (PaddingException e) {
            e.updateHeader(hdr);
            throw e;
        }
        this.lastFileOffset = FitsUtil.findOffset(this.dataStr);
        BasicHDU<?> nextHDU = FitsFactory.HDUFactory(hdr, data);
        this.hduList.add(nextHDU);
        return nextHDU;
    }

    /** Read to the end of the associated input stream */
    private void readToEnd() throws FitsException {

        while (this.dataStr != null && !this.atEOF) {
            try {
                if (readHDU() == null) {
                    break;
                }
            } catch (EOFException e) {
                if (FitsFactory.getAllowTerminalJunk() && e.getCause() instanceof TruncatedFileException) {
                    if (getNumberOfHDUs() > 0) {
                        this.atEOF = true;
                        return;
                    }
                }
                throw new FitsException("IO error: " + e);
            } catch (IOException e) {
                throw new FitsException("IO error: " + e);
            }
        }
    }

    /**
     * Add or Modify the CHECKSUM keyword in all headers. by R J Mathar
     * 
     * @throws nom.tam.fits.HeaderCardException
     * @throws nom.tam.fits.FitsException
     * @since 2005-10-05
     */
    public void setChecksum() throws nom.tam.fits.HeaderCardException, nom.tam.fits.FitsException, java.io.IOException {
        for (int i = 0; i < getNumberOfHDUs(); i += 1) {
            setChecksum(getHDU(i));
        }
    }

    /**
     * Set the data stream to be used for future input.
     * 
     * @param stream
     *            The data stream to be used.
     */
    public void setStream(ArrayDataInput stream) {
        this.dataStr = stream;
        this.atEOF = false;
        this.lastFileOffset = -1;
    }

    /**
     * Return the number of HDUs in the Fits object. If the FITS file is
     * associated with an external stream make sure that we have exhausted the
     * stream.
     * 
     * @return number of HDUs.
     * @deprecated The meaning of size of ambiguous. Use
     */
    @Deprecated
    public int size() throws FitsException {
        readToEnd();
        return getNumberOfHDUs();
    }

    /**
     * Skip the next HDU on the default input stream.
     */
    public void skipHDU() throws FitsException, IOException {
        if (this.atEOF) {
            return;
        } else {
            Header hdr = new Header(this.dataStr);
            int dataSize = (int) hdr.getDataSize();
            this.dataStr.skip(dataSize);
            if (this.dataStr instanceof nom.tam.util.RandomAccess) {
                this.lastFileOffset = ((RandomAccess) this.dataStr).getFilePointer();
            }
        }
    }

    /**
     * Skip HDUs on the associate input stream.
     * 
     * @param n
     *            The number of HDUs to be skipped.
     */
    public void skipHDU(int n) throws FitsException, IOException {
        for (int i = 0; i < n; i += 1) {
            skipHDU();
        }
    }

    /**
     * Initialize the input stream. Mostly this checks to see if the stream is
     * compressed and wraps the stream if necessary. Even if the stream is not
     * compressed, it will likely be wrapped in a PushbackInputStream. So users
     * should probably not supply a BufferedDataInputStream themselves, but
     * should allow the Fits class to do the wrapping.
     * 
     * @param str
     * @throws FitsException
     */
    protected void streamInit(InputStream str) throws FitsException {
        str = CompressionManager.decompress(str);
        if (str instanceof ArrayDataInput) {
            this.dataStr = (ArrayDataInput) str;
        } else {
            // Use efficient blocking for input.
            this.dataStr = new BufferedDataInputStream(str);
        }
    }

    /**
     * Initialize the stream.
     * 
     * @param str
     *            The user specified input stream
     * @param seekable
     *            ignored
     */
    protected void streamInit(InputStream str, boolean seekable) throws FitsException {
        streamInit(str);
    }

    /**
     * Write a Fits Object to an external Stream.
     * 
     * @param os
     *            A DataOutput stream.
     */
    public void write(DataOutput os) throws FitsException {

        ArrayDataOutput obs;
        boolean newOS = false;

        if (os instanceof ArrayDataOutput) {
            obs = (ArrayDataOutput) os;
        } else if (os instanceof DataOutputStream) {
            newOS = true;
            obs = new BufferedDataOutputStream((DataOutputStream) os);
        } else {
            throw new FitsException("Cannot create ArrayDataOutput from class " + os.getClass().getName());
        }

        BasicHDU<?> hh;
        for (int i = 0; i < getNumberOfHDUs(); i += 1) {
            try {
                hh = this.hduList.get(i);
                hh.write(obs);
            } catch (ArrayIndexOutOfBoundsException e) {
                e.printStackTrace();
                throw new FitsException("Internal Error: Vector Inconsistency" + e);
            }
        }
        if (newOS) {
            try {
                obs.flush();
                obs.close();
            } catch (IOException e) {
                System.err.println("Warning: error closing FITS output stream");
            }
        }
        try {
            if (obs instanceof BufferedFile) {
                ((BufferedFile) obs).setLength(((BufferedFile) obs).getFilePointer());
            }
        } catch (IOException e) {
            // Ignore problems...
        }

    }

    @Override
    public void close() throws IOException {
        if (dataStr != null) {
            this.dataStr.close();
        }
    }
}
