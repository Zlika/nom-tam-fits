package nom.tam.image.comp.rice;

/*
 * #%L
 * nom.tam FITS library
 * %%
 * Copyright (C) 1996 - 2015 nom-tam-fits
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

import nom.tam.fits.header.Compression;
import nom.tam.image.comp.ICompressOption;
import nom.tam.util.PrimitiveTypeEnum;

public class RiceCompressOption implements ICompressOption {

    public static final int DEFAULT_RISE_BLOCKSIZE = 32;

    public static final int DEFAULT_RISE_BYTEPIX = PrimitiveTypeEnum.INT.size();

    private int blockSize = DEFAULT_RISE_BLOCKSIZE;

    private Integer bytePix = null;

    private RiceCompressOption original;

    @Override
    public RiceCompressOption copy() {
        try {
            RiceCompressOption clone = (RiceCompressOption) clone();
            clone.original = this;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("open could not be cloned", e);
        }
    }

    public int getBlockSize() {
        return this.blockSize;
    }

    @Override
    public double getBScale() {
        return Double.NaN;
    }

    public int getBytePix() {
        return this.bytePix;
    }

    @Override
    public double getBZero() {
        return Double.NaN;
    }

    @Override
    public Parameter[] getCompressionParameters() {
        return new Parameter[]{
            new Parameter(Compression.BLOCKSIZE, this.blockSize),
            new Parameter(Compression.BYTEPIX, this.bytePix),
        };
    }

    public RiceCompressOption setBlockSize(int value) {
        this.blockSize = value;
        return this;
    }

    @Override
    public ICompressOption setBNull(Integer blank) {
        return this;
    }

    @Override
    public ICompressOption setBScale(double scale) {
        return this;
    }

    public RiceCompressOption setBytePix(int value) {
        this.bytePix = value;
        return this;
    }

    @Override
    public ICompressOption setBZero(double zero) {
        return this;
    }

    @Override
    public ICompressOption setCompressionParameter(Parameter[] parameters) {
        for (Parameter parameter : parameters) {
            if (Compression.BLOCKSIZE.equals(parameter.getName())) {
                setBlockSize(parameter.getValue(Integer.class));
            } else if (Compression.BYTEPIX.equals(parameter.getName())) {
                setBytePix(parameter.getValue(Integer.class));
            }
        }
        return setDefaultBytePix(DEFAULT_RISE_BYTEPIX);
    }

    protected RiceCompressOption setDefaultBytePix(int defaultBytePix) {
        if (this.original != null) {
            this.original.setDefaultBytePix(defaultBytePix);
            this.bytePix = this.original.getBytePix();
        } else if (this.bytePix == null) {
            this.bytePix = defaultBytePix;
        }
        return this;
    }

    @Override
    public RiceCompressOption setTileHeigth(int value) {
        return this;
    }

    @Override
    public RiceCompressOption setTileWidth(int value) {
        return this;
    }
}
