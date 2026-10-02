package com.sportday.service;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that the marking-sheet font renders the characters it is asked to.
 *
 * <p>A font can load without complaint and still put the wrong glyph on the page,
 * which on a name list would be the worst possible failure. To catch that, the
 * same text is rendered twice — once through the PDF library the application uses,
 * once through an independent PDF implementation reading the very same font file —
 * and the ink is compared. If both agree pixel for pixel, the library is mapping
 * code points to glyphs the way the font intends.</p>
 *
 * <p>The comparison is sensitive: feeding the two renderers <em>different</em>
 * fonts produces a large difference, which is what the last test asserts, so a
 * silent regression to always-equal output would be noticed.</p>
 */
class PdfFontGlyphMappingTest {

    /** Column headings plus characters that previously looked suspicious. */
    private static final String PROBE = "田大文級別學號";

    private static final float PAGE_WIDTH = 340f;
    private static final float PAGE_HEIGHT = 110f;
    private static final float FONT_SIZE = 42f;
    private static final float TEXT_X = 12f;
    private static final float TEXT_Y = 32f;
    private static final int DPI = 200;
    private static final int INK_THRESHOLD = 200;

    /** Above this Jaccard distance the two renderings show different glyphs. */
    private static final double MISMATCH_LIMIT = 0.20;

    @Test
    @DisplayName("the resolved marking-sheet font renders the right glyphs")
    void resolvedFontMapsGlyphsCorrectly() throws Exception {
        PdfFontProvider provider = new PdfFontProvider("");
        String resolvedFrom = provider.getResolvedFrom();
        File fontFile = new File(resolvedFrom.substring(resolvedFrom.lastIndexOf('[') + 1,
                resolvedFrom.lastIndexOf(']')));
        assumeTrue(fontFile.isFile(), "resolved font is not a readable file: " + resolvedFrom);

        double mismatch = mismatch(fontFile, fontFile, PROBE);
        assertTrue(mismatch <= MISMATCH_LIMIT, String.format(
                "The resolved font %s renders different glyphs from the same font read independently "
                        + "(ink mismatch %.1f%%, limit %.0f%%).",
                fontFile.getName(), mismatch * 100, MISMATCH_LIMIT * 100));
    }

    @Test
    @DisplayName("the comparison has teeth: different fonts do not match")
    void comparisonDetectsDifferentFonts() throws Exception {
        File heavy = firstExisting("C:/Windows/Fonts/simhei.ttf");
        File light = firstExisting("C:/Windows/Fonts/NotoSansHK-VF.ttf",
                "C:/Windows/Fonts/NotoSansTC-VF.ttf");
        assumeTrue(heavy != null && light != null, "two distinct CJK fonts are needed for this check");

        double mismatch = mismatch(heavy, light, PROBE);
        assertTrue(mismatch > MISMATCH_LIMIT, String.format(
                "Two different typefaces scored only %.1f%% apart — the comparison is no longer "
                        + "sensitive enough to catch a mis-mapped font.", mismatch * 100));
    }

    @Test
    @DisplayName("every candidate font can be opened and renders its own glyphs faithfully")
    void candidatesAreFaithful() throws Exception {
        PdfFontProvider provider = new PdfFontProvider("");
        int checked = 0;
        for (String path : new String[]{
                "C:/Windows/Fonts/NotoSansHK-VF.ttf",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/kaiu.ttf"}) {
            File file = new File(path);
            if (!file.isFile()) {
                continue;
            }
            checked++;
            double mismatch = mismatch(file, file, PROBE);
            assertTrue(mismatch <= MISMATCH_LIMIT,
                    path + " does not round-trip faithfully (" + String.format("%.1f%%", mismatch * 100) + ")");
        }
        assumeTrue(checked > 0, "no candidate fonts present on this machine");
        assertNotNull(provider.baseFont());
    }

    @Test
    @DisplayName("the sheet page sizes are the expected A sizes")
    void pageSizesAreCorrect() {
        assertEquals(421f, PageSize.A5.getWidth(), 1f);
        assertEquals(595f, PageSize.A5.getHeight(), 1f);
        assertEquals(595f, PageSize.A4.getWidth(), 1f);
        assertEquals(842f, PageSize.A4.getHeight(), 1f);
    }

    private static File firstExisting(String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    /** Convenience: same font on both sides. */
    private double mismatch(File fontFile, String text) throws Exception {
        return mismatch(fontFile, fontFile, text);
    }

    /**
     * Jaccard distance between the two renderings' ink masks: 0 means the same
     * pixels are inked, 1 means completely different.
     *
     * @param libraryFont font used by the PDF library under test
     * @param referenceFont font used by the independent renderer
     */
    private double mismatch(File libraryFont, File referenceFont, String text) throws Exception {
        boolean[] viaLibrary = inkMask(renderWithPdfLibrary(libraryFont, text));
        boolean[] viaPdfBox = inkMask(renderWithPdfBox(referenceFont, text));

        assertEquals(viaLibrary.length, viaPdfBox.length, "renderings must be the same size");
        int both = 0;
        int either = 0;
        for (int i = 0; i < viaLibrary.length; i++) {
            if (viaLibrary[i] && viaPdfBox[i]) {
                both++;
            }
            if (viaLibrary[i] || viaPdfBox[i]) {
                either++;
            }
        }
        assertTrue(either > 200, "the probe text produced almost no ink — nothing was compared");
        return 1.0 - ((double) both / either);
    }

    /** Renders the probe with the library the application actually uses. */
    private byte[] renderWithPdfLibrary(File fontFile, String text) throws Exception {
        BaseFont base = BaseFont.createFont(fontFile.getAbsolutePath(),
                BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        Document document = new Document(new com.lowagie.text.Rectangle(PAGE_WIDTH, PAGE_HEIGHT), 0, 0, 0, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();
        // Place the text on an explicit baseline so both renderings line up and
        // the comparison measures glyph shapes rather than layout.
        com.lowagie.text.pdf.PdfContentByte canvas = writer.getDirectContent();
        canvas.beginText();
        canvas.setFontAndSize(base, FONT_SIZE);
        canvas.setTextMatrix(TEXT_X, TEXT_Y);
        canvas.showText(text);
        canvas.endText();
        document.close();
        return out.toByteArray();
    }

    /** Renders the probe with an independent implementation. */
    private byte[] renderWithPdfBox(File fontFile, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
            document.addPage(page);
            PDType0Font font = PDType0Font.load(document, fontFile);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, FONT_SIZE);
                content.newLineAtOffset(TEXT_X, TEXT_Y);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private boolean[] inkMask(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, DPI, ImageType.GRAY);
            boolean[] mask = new boolean[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    mask[y * image.getWidth() + x] = (image.getRGB(x, y) & 0xFF) < INK_THRESHOLD;
                }
            }
            return mask;
        }
    }
}
