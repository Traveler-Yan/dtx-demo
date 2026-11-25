package com.example.pdf;

import com.lowagie.text.pdf.BaseFont;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a well-formed XHTML document into an A4 PDF using Flying Saucer + OpenPDF.
 *
 * <p>Usage: {@code HtmlToPdf <input.xhtml> <output.pdf> [fontDir]}</p>
 */
public final class HtmlToPdf {

    /** Font families we try to embed so that Chinese and code glyphs always render. */
    private static final String[] FONT_FILES = {
            "msyh.ttc", "msyhbd.ttc",
            "simhei.ttf",
            "simsun.ttc",
            "Deng.ttf", "Dengb.ttf",
            "consola.ttf", "consolab.ttf", "consolai.ttf"
    };

    private HtmlToPdf() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: HtmlToPdf <input.xhtml> <output.pdf> [fontDir]");
            System.exit(2);
        }
        Path input = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        String fontDir = args.length > 2 ? args[2] : "C:\\Windows\\Fonts";

        String html = Files.readString(input, StandardCharsets.UTF_8);
        if (!html.isEmpty() && html.charAt(0) == '\uFEFF') {
            html = html.substring(1); // strip UTF-8 BOM, XML parsers reject it
        }

        ITextRenderer renderer = new ITextRenderer();
        renderer.getSharedContext().setPrint(true);
        renderer.getSharedContext().setInteractive(false);
        renderer.getSharedContext().setMedia("print");

        List<String> embedded = new ArrayList<>();
        for (String name : FONT_FILES) {
            File f = new File(fontDir, name);
            if (!f.isFile()) {
                continue;
            }
            try {
                renderer.getFontResolver()
                        .addFont(f.getAbsolutePath(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                embedded.add(name);
            } catch (Exception ignored) {
                // A .ttc collection without a face index may fail; skip it and keep going.
            }
        }
        System.out.println("[pdfgen] embedded fonts: " + embedded);

        renderer.setDocumentFromString(html, input.getParent().toUri().toString());
        renderer.layout();

        Files.createDirectories(output.getParent());
        try (OutputStream os = new FileOutputStream(output.toFile())) {
            renderer.createPDF(os, true);
        }
        System.out.println("[pdfgen] written " + output + " (" + Files.size(output) + " bytes)");
    }
}
