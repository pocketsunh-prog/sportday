package com.sportday.service;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplies the font used on marking sheets.
 *
 * <p>Student and event names are frequently Chinese, and the 14 standard PDF
 * fonts cannot encode them. This component therefore looks for a
 * Unicode-capable TrueType font in a fixed order of preference:</p>
 *
 * <ol>
 *   <li>a font bundled by the school at {@code classpath:/fonts/cjk.ttf};</li>
 *   <li>the {@code app.pdf.font-path} setting (or {@code SPORTDAY_PDF_FONT});</li>
 *   <li>well-known system font locations on Windows, Linux and macOS.</li>
 * </ol>
 *
 * <p>If none is found the sheet still renders with the built-in Helvetica —
 * Latin text only — and a warning is logged, so a missing font degrades the
 * output instead of breaking the sport day.</p>
 */
@Slf4j
@Component
public class PdfFontProvider {

    /** Glyphs used to confirm a candidate font really covers Chinese text. */
    private static final String CJK_PROBE = "學號姓名組別成績備註徑項田項";

    private final String configuredPath;

    private volatile BaseFont baseFont;
    private volatile String resolvedFrom;
    private volatile boolean unicodeCapable;

    public PdfFontProvider(@Value("${app.pdf.font-path:}") String configuredPath) {
        this.configuredPath = configuredPath == null || configuredPath.isBlank() ? null : configuredPath.trim();
    }

    @PostConstruct
    void resolveAtStartup() {
        try {
            BaseFont font = resolve();
            log.info("Marking-sheet font: {} ({})", font.getPostscriptFontName(),
                    unicodeCapable ? "unicode/CJK capable" : "Latin only");
            log.info("Marking-sheet font resolved from: {}", resolvedFrom);
        } catch (Exception ex) {
            log.warn("Could not pre-resolve the marking-sheet font: {}", ex.getMessage());
        }
    }

    /** The font to draw with. Never null. */
    public BaseFont baseFont() {
        BaseFont font = baseFont;
        if (font == null) {
            synchronized (this) {
                if (baseFont == null) {
                    baseFont = resolve();
                }
                font = baseFont;
            }
        }
        return font;
    }

    /** True when the resolved font can encode Chinese text. */
    public boolean isUnicodeCapable() {
        baseFont();
        return unicodeCapable;
    }

    /** Where the font came from, for the admin diagnostics view. */
    public String getResolvedFrom() {
        baseFont();
        return resolvedFrom;
    }

    public Font font(float size) {
        return new Font(baseFont(), size);
    }

    public Font boldFont(float size) {
        return new Font(baseFont(), size, Font.BOLD);
    }

    private BaseFont resolve() {
        List<Candidate> candidates = candidates();
        IOException lastFailure = null;

        for (Candidate candidate : candidates) {
            try {
                BaseFont font = load(candidate);
                if (font == null) {
                    continue;
                }
                boolean coversCjk = coversCjk(font);
                // A bundled or explicitly configured font wins even if it lacks
                // CJK; otherwise prefer a font that can actually print names.
                if (candidate.premium() || coversCjk || candidate.lastResort()) {
                    this.unicodeCapable = coversCjk;
                    this.resolvedFrom = candidate.describe();
                    return font;
                }
            } catch (IOException ex) {
                lastFailure = ex;
                log.debug("Font candidate {} not usable: {}", candidate.describe(), ex.getMessage());
            }
        }

        this.unicodeCapable = false;
        this.resolvedFrom = "built-in Helvetica (no TrueType font found)";
        if (lastFailure != null) {
            log.debug("Last font failure", lastFailure);
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception ex) {
            throw new IllegalStateException("No usable PDF font at all", ex);
        }
    }

    private BaseFont load(Candidate candidate) throws IOException {
        if (candidate.isClasspath()) {
            ClassPathResource resource = new ClassPathResource(candidate.path());
            if (!resource.exists()) {
                return null;
            }
            // BaseFont needs a real file for embedding, so copy the resource out.
            Path temp = Files.createTempFile("sportday-font-", ".ttf");
            temp.toFile().deleteOnExit();
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return createFont(temp.toAbsolutePath().toString(), candidate.embedded());
        }

        File file = new File(candidate.path());
        if (!file.isFile() || file.length() == 0) {
            return null;
        }
        return createFont(file.getAbsolutePath(), candidate.embedded());
    }

    private BaseFont createFont(String path, boolean embedded) throws IOException {
        try {
            return BaseFont.createFont(path, BaseFont.IDENTITY_H, embedded);
        } catch (Exception ex) {
            // Fall back to the font's own encoding before giving up on it.
            try {
                return BaseFont.createFont(path, BaseFont.WINANSI, embedded);
            } catch (Exception second) {
                throw new IOException("BaseFont refused " + path + ": " + second.getMessage(), second);
            }
        }
    }

    private boolean coversCjk(BaseFont font) {
        try {
            for (int i = 0; i < CJK_PROBE.length(); i++) {
                if (!font.charExists(CJK_PROBE.charAt(i))) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();

        // 1. A font the school dropped into the jar/resources.
        list.add(new Candidate("/fonts/cjk.ttf", true, true, false, "bundled classpath:/fonts/cjk.ttf"));

        // 2. Operator override.
        if (configuredPath != null) {
            list.add(new Candidate(configuredPath, false, true, false, "app.pdf.font-path=" + configuredPath));
        }

        // 3. System fonts, in preference order.
        //
        // Only single-font .ttf files are listed: this PDF library cannot open a
        // TrueType collection (.ttc), which is how Noto CJK, Microsoft JhengHei,
        // PingFang, SimSun and WenQuanYi are usually shipped. Such files fail
        // with "Font ... with 'Identity-H' is not recognized".
        //
        // A clean sans (Noto Sans, SimHei) is preferred over the traditional
        // brush face 標楷體 kaiu.ttf: kaiu is perfectly correct, but its hairline
        // strokes drop out at the 8-9pt used on an A5 sheet, so a name can read
        // as the wrong character on paper even though the PDF is right.
        String[][] systemFonts = {
                // Windows — Hong Kong/Traditional first, then Simplified.
                {"C:/Windows/Fonts/NotoSansHK-VF.ttf", "Windows Noto Sans HK"},
                {"C:/Windows/Fonts/NotoSansTC-VF.ttf", "Windows Noto Sans TC"},
                {"C:/Windows/Fonts/NotoSansSC-VF.ttf", "Windows Noto Sans SC"},
                {"C:/Windows/Fonts/simhei.ttf", "Windows 黑體 SimHei"},
                {"C:/Windows/Fonts/ARIALUNI.TTF", "Windows Arial Unicode"},
                // Linux — the Debian/Ubuntu CJK packages that ship real .ttf files.
                {"/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf", "Linux Droid Sans Fallback"},
                {"/usr/share/fonts/truetype/arphic/uming.ttf", "Linux AR PL UMing"},
                {"/usr/share/fonts/truetype/arphic/ukai.ttf", "Linux AR PL UKai"},
                {"/usr/share/fonts/truetype/wqy/wqy-microhei.ttf", "Linux WenQuanYi Micro Hei"},
                {"/usr/share/fonts/truetype/unifont/unifont.ttf", "Linux Unifont (Latin fallback)"},
                // macOS
                {"/Library/Fonts/Arial Unicode.ttf", "macOS Arial Unicode"},
                {"/System/Library/Fonts/Supplemental/Arial Unicode.ttf", "macOS Arial Unicode"},
        };
        for (int i = 0; i < systemFonts.length; i++) {
            String[] entry = systemFonts[i];
            // The final entry may be a Latin-only font: better than nothing.
            boolean lastResort = i == systemFonts.length - 1;
            list.add(new Candidate(entry[0], false, false, lastResort, entry[1]));
        }
        return list;
    }

    private record Candidate(String path, boolean isClasspath, boolean premium,
                             boolean lastResort, String source) {
        boolean embedded() {
            return true;
        }

        String describe() {
            return source + " [" + path + "]";
        }
    }
}
