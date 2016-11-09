package org.verapdf.pd.font.cff;

import org.verapdf.io.SeekableStream;
import org.verapdf.pd.font.CFFNumber;
import org.verapdf.pd.font.FontProgram;
import org.verapdf.pd.font.cmap.CMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Instance of this class represent a parser of CIDFont from FontSet of CFF file.
 *
 * @author Sergey Shemyakov
 */
public class CFFCIDFontProgram extends CFFFontBaseParser implements FontProgram {

    private long fdArrayOffset;
    private long fdSelectOffset;
    private Map<Integer, Integer> charSet;  // mapping cid -> gid
    private boolean isDefaultCharSet = false;
    private int[] fdSelect;     // array with mapping gid -> font dict
    private int[] nominalWidths;
    private int[] defaultWidths;
    private int supplement;
    private String registry;
    private String ordering;
    private boolean fontParsed = false;

    private CMap externalCMap;

    CFFCIDFontProgram(SeekableStream stream, CFFIndex definedNames, CFFIndex globalSubrs,
                      long topDictBeginOffset, long topDictEndOffset, CMap externalCMap) {
        super(stream);
        this.definedNames = definedNames;
        this.globalSubrs = globalSubrs;
        this.topDictBeginOffset = topDictBeginOffset;
        this.topDictEndOffset = topDictEndOffset;
        this.externalCMap = externalCMap;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void parseFont() throws IOException {
        if (!fontParsed) {
            fontParsed = true;
            this.source.seek(topDictBeginOffset);
            while (this.source.getOffset() < topDictEndOffset) {
                readTopDictUnit();
            }
            this.stack.clear();

            this.source.seek(charStringsOffset);
            this.readCharStrings();

            this.source.seek(charSetOffset);
            this.readCharSet();

            this.source.seek(fdSelectOffset);
            this.readFDSelect();

            this.source.seek(fdArrayOffset);
            this.readFontDicts();

            this.readWidths();
        }
    }

    @Override
    protected void readTopDictTwoByteOps(int lastRead) throws IOException {
        switch (lastRead) {
            case 30:
                this.supplement = (int) this.stack.get(this.stack.size() - 1).getInteger();
                this.ordering = getStringBySID((int)
                        this.stack.get(this.stack.size() - 2).getInteger());
                this.registry = getStringBySID((int)
                        this.stack.get(this.stack.size() - 3).getInteger());
                this.stack.clear();
                break;
            case 36:
                this.fdArrayOffset = this.stack.get(this.stack.size() - 1).getInteger();
                this.stack.clear();
                break;
            case 37:
                this.fdSelectOffset = this.stack.get(this.stack.size() - 1).getInteger();
                this.stack.clear();
                break;
            default:
                this.stack.clear();
        }
    }

    private void readCharSet() throws IOException {
        this.charSet = new HashMap<>(nGlyphs);
        this.charSet.put(0, 0);
        int format = this.readCard8();
        switch (format) {
            case 0:
                for (int i = 1; i < nGlyphs; ++i) {
                    this.charSet.put(this.readCard16(), i);
                }
                break;
            case 1:
            case 2:
                int charSetPointer = 1;
                while (charSetPointer < nGlyphs) {
                    int first = this.readCard16();
                    int nLeft;
                    if (format == 1) {
                        nLeft = this.readCard8() & 0xFF;
                    } else {
                        nLeft = this.readCard16();
                    }
                    for (int i = 0; i <= nLeft; ++i) {
                        this.charSet.put(first + i, charSetPointer++);
                    }
                }
                break;
            default:
                isDefaultCharSet = true;
        }
    }

    private void readFDSelect() throws IOException {
        try {
            int format = this.readCard8();
            this.fdSelect = new int[nGlyphs];
            if (format == 0) {
                for (int i = 0; i < nGlyphs; ++i) {
                    this.fdSelect[i] = this.readCard8();
                }
            } else if (format == 3) {
                int numberOfRanges = this.readCard16();
                int first = this.readCard16();
                for (int i = 0; i < numberOfRanges; ++i) {
                    int fd = this.readCard8();
                    int afterLast = this.readCard16();
                    for (int j = first; j < afterLast; ++j) {
                        this.fdSelect[j] = fd;
                    }
                    first = afterLast;
                }
            } else {
                throw new IOException("Can't parse format of FDSelect in CFF file");
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("Can't parse FDSelect in CFF file", e);
        }
    }

    private void readFontDicts() throws IOException {
        CFFIndex fontDictIndex = this.readIndex();
        this.nominalWidths = new int[fontDictIndex.size()];
        this.defaultWidths = new int[fontDictIndex.size()];
        for (int i = 0; i < fontDictIndex.size(); ++i) {
            this.readTopDict(fontDictIndex.getOffset(i) + fdArrayOffset +
                    fontDictIndex.getOffsetShift(),
                    fontDictIndex.getOffset(i + 1) + fdArrayOffset +
                            fontDictIndex.getOffsetShift());
            this.readPrivateDict(this.privateDictOffset, this.privateDictSize);
            this.nominalWidths[i] = this.nominalWidthX;
            this.defaultWidths[i] = this.defaultWidthX;
        }
    }

    private void readPrivateDict(long from, long size) throws IOException {
        this.stack.clear();
        long startingOffset = this.source.getOffset();
        this.source.seek(from);
        while (this.source.getOffset() < from + size) {
            this.readPrivateDictUnit();
        }
        this.source.seek(startingOffset);
        this.readLocalSubrsAndBias();
    }

    private void readTopDict(long from, long to) throws IOException {
        this.stack.clear();
        long startingOffset = this.source.getOffset();
        this.source.seek(from);
        while (this.source.getOffset() < to) {
            this.readTopDictUnit();
        }
        this.source.seek(startingOffset);

    }

    private void readWidths() throws IOException {
        for (int i = 0; i < nGlyphs; ++i) {
            CFFNumber width = getWidthFromCharString(this.charStrings.get(i));
            float res = width.isInteger() ? width.getInteger() :
                    width.getReal();
            if (res == -1.) {
                res = this.defaultWidths[this.fdSelect[i]];
            } else {
                res += this.nominalWidths[this.fdSelect[i]];
            }
            this.widths[i] = res;
        }
        if (!Arrays.equals(this.fontMatrix, DEFAULT_FONT_MATRIX)) {
            for (int i = 0; i < widths.length; ++i) {
                widths[i] = widths[i] * (fontMatrix[0] * 1000);
            }
        }
    }

    /**
     * Gets glyph ID for given character ID.
     *
     * @param cid is character ID.
     * @return glyph ID or null if character is not in font.
     */
    public Integer getGid(int cid) {
        if (isDefaultCharSet) {
            return cid;
        }
        return this.charSet.get(cid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getWidth(int code) {
        int cid = this.externalCMap.toCID(code);
        Integer gid = getGid(cid);
        return (gid == null || gid == 0) ? -1 : widths[gid];
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getWidth(String glyphName) {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsCode(int code) {
        int cid = externalCMap.toCID(code);
        return this.externalCMap.containsCode(code) &&
                this.charSet.get(cid) != null &&
                this.charSet.get(cid) != 0;
    }

    public int getSupplement() {
        return supplement;
    }

    public String getRegistry() {
        return registry;
    }

    public String getOrdering() {
        return ordering;
    }
}
