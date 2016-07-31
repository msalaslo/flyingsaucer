/*
 * {{{ header & license
 * Copyright (c) 2006 Wisconsin Court System
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.	See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * }}}
 */
package org.xhtmlrenderer.pdf;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Shape;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xhtmlrenderer.context.StyleReference;
import org.xhtmlrenderer.css.style.CalculatedStyle;
import org.xhtmlrenderer.event.DocTagListenerAccessible;
import org.xhtmlrenderer.event.DocTagListenerAccessibleImpl;
import org.xhtmlrenderer.extend.NamespaceHandler;
import org.xhtmlrenderer.extend.UserInterface;
import org.xhtmlrenderer.layout.BoxBuilder;
import org.xhtmlrenderer.layout.Layer;
import org.xhtmlrenderer.layout.LayoutContext;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.render.BlockBox;
import org.xhtmlrenderer.render.PageBox;
import org.xhtmlrenderer.render.RenderingContext;
import org.xhtmlrenderer.render.ViewportBox;
import org.xhtmlrenderer.resource.XMLResource;
import org.xhtmlrenderer.simple.extend.XhtmlNamespaceHandler;
import org.xhtmlrenderer.util.Configuration;
import org.xml.sax.InputSource;

import com.itextpdf.text.DocListener;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.exceptions.IllegalPdfSyntaxException;
import com.itextpdf.text.pdf.PdfWriter;

/**
 * Rewrites Flyin Saurcer class org.xhtmlrenderer.pdf.ITextRenderer for supporting PDF/UA generation
 * Delegates PDF/UA operations on org.xhtmlrenderer.pdf.ITextRendererAccessible
 *
 */
public class ITextRenderer {
    // These two defaults combine to produce an effective resolution of 96 px to
    // the inch
    private static final float DEFAULT_DOTS_PER_POINT = 20f * 4f / 3f;
    private static final int DEFAULT_DOTS_PER_PIXEL = 20;

    private final SharedContext _sharedContext;
    private final ITextOutputDevice _outputDevice;

    private Document _doc;
    private BlockBox _root;

    private final float _dotsPerPoint;

    private com.itextpdf.text.Document _pdfDoc;
    private PdfWriter _writer;

    private PDFEncryption _pdfEncryption;

    // note: not hard-coding a default version in the _pdfVersion field as this
    // may change between iText releases
    // check for null before calling writer.setPdfVersion()
    // use one of the values in PDFWriter.VERSION...
    private Character _pdfVersion;

    private final char[] validPdfVersions = new char[] { PdfWriter.VERSION_1_2, PdfWriter.VERSION_1_3, PdfWriter.VERSION_1_4,
            PdfWriter.VERSION_1_5, PdfWriter.VERSION_1_6, PdfWriter.VERSION_1_7 };

    private PDFCreationListener _listener;

    public ITextRenderer() {
        this(DEFAULT_DOTS_PER_POINT, DEFAULT_DOTS_PER_PIXEL);
    }

    public ITextRenderer(float dotsPerPoint, int dotsPerPixel) {
        _dotsPerPoint = dotsPerPoint;

        _outputDevice = new ITextOutputDevice(_dotsPerPoint);

        ITextUserAgent userAgent = new ITextUserAgent(_outputDevice);
        _sharedContext = new SharedContext();
        _sharedContext.setUserAgentCallback(userAgent);
        _sharedContext.setCss(new StyleReference(userAgent));
        userAgent.setSharedContext(_sharedContext);
        _outputDevice.setSharedContext(_sharedContext);

        ITextFontResolver fontResolver = new ITextFontResolver(_sharedContext);
        _sharedContext.setFontResolver(fontResolver);

        ITextReplacedElementFactory replacedElementFactory = new ITextReplacedElementFactory(_outputDevice);
        _sharedContext.setReplacedElementFactory(replacedElementFactory);

        _sharedContext.setTextRenderer(new ITextTextRenderer());
        _sharedContext.setDPI(72 * _dotsPerPoint);
        _sharedContext.setDotsPerPixel(dotsPerPixel);
        _sharedContext.setPrint(true);
        _sharedContext.setInteractive(false);
    }

    public Document getDocument() {
        return _doc;
    }

    public ITextFontResolver getFontResolver() {
        return (ITextFontResolver) _sharedContext.getFontResolver();
    }

    private Document loadDocument(final String uri) {
        return _sharedContext.getUac().getXMLResource(uri).getDocument();
    }

    public void setDocument(String uri) {
        setDocument(loadDocument(uri), uri);
    }

    public void setDocument(Document doc, String url) {
        setDocument(doc, url, new XhtmlNamespaceHandler());
    }

    public void setDocument(File file) throws IOException {

        File parent = file.getAbsoluteFile().getParentFile();
        setDocument(loadDocument(file.toURI().toURL().toExternalForm()), (parent == null ? "" : parent.toURI().toURL().toExternalForm()));
    }

    public void setDocumentFromString(String content) {
        setDocumentFromString(content, null);
    }

    public void setDocumentFromString(String content, String baseUrl) {
        InputSource is = new InputSource(new BufferedReader(new StringReader(content)));
        Document dom = XMLResource.load(is).getDocument();

        setDocument(dom, baseUrl);
    }

    public void setDocument(Document doc, String url, NamespaceHandler nsh) {
        _doc = doc;

        getFontResolver().flushFontFaceFonts();

        _sharedContext.reset();
        if (Configuration.isTrue("xr.cache.stylesheets", true)) {
            _sharedContext.getCss().flushStyleSheets();
        } else {
            _sharedContext.getCss().flushAllStyleSheets();
        }
        _sharedContext.setBaseURL(url);
        _sharedContext.setNamespaceHandler(nsh);
        _sharedContext.getCss().setDocumentContext(_sharedContext, _sharedContext.getNamespaceHandler(), doc, new NullUserInterface());
        getFontResolver().importFontFaces(_sharedContext.getCss().getFontFaceRules());
    }

    public PDFEncryption getPDFEncryption() {
        return _pdfEncryption;
    }

    public void setPDFEncryption(PDFEncryption pdfEncryption) {
        _pdfEncryption = pdfEncryption;
    }

    public void setPDFVersion(char _v) {
        for (int i = 0; i < validPdfVersions.length; i++) {
            if (_v == validPdfVersions[i]) {
                _pdfVersion = new Character(_v);
                return;
            }
        }
        throw new IllegalArgumentException("Invalid PDF version character; use "
                + "valid constants from PdfWriter (e.g. PdfWriter.VERSION_1_2)");
    }

    public char getPDFVersion() {
        return _pdfVersion == null ? '0' : _pdfVersion.charValue();
    }

    public void layout() {
        LayoutContext c = newLayoutContext();
        BlockBox root = BoxBuilder.createRootBox(c, _doc);
        root.setContainingBlock(new ViewportBox(getInitialExtents(c)));
        root.layout(c);
        Dimension dim = root.getLayer().getPaintingDimension(c);
        root.getLayer().trimEmptyPages(c, dim.height);
        root.getLayer().layoutPages(c);
        _root = root;
    }

    private Rectangle getInitialExtents(LayoutContext c) {
        PageBox first = Layer.createPageBox(c, "first");

        return new Rectangle(0, 0, first.getContentWidth(c), first.getContentHeight(c));
    }

    private RenderingContext newRenderingContext() {
        RenderingContext result = _sharedContext.newRenderingContextInstance();
        result.setFontContext(new ITextFontContext());

        result.setOutputDevice(_outputDevice);

        _sharedContext.getTextRenderer().setup(result.getFontContext());

        result.setRootLayer(_root.getLayer());

        return result;
    }

    private LayoutContext newLayoutContext() {
        LayoutContext result = _sharedContext.newLayoutContextInstance();
        result.setFontContext(new ITextFontContext());

        _sharedContext.getTextRenderer().setup(result.getFontContext());

        return result;
    }
   
    public void createPDF(OutputStream os) throws DocumentException, IOException {
        createPDF(os, true, 0, null);
    }
    
    public void createPDF(OutputStream os, String language) throws DocumentException, IOException {
        createPDF(os, true, 0, language);
    }

    public void writeNextDocument() throws DocumentException, IOException {
        writeNextDocument(0);
    }

    public void writeNextDocument(int initialPageNo) throws DocumentException, IOException {
        List pages = _root.getLayer().getPages();

        RenderingContext c = newRenderingContext();
        c.setInitialPageNo(initialPageNo);
        PageBox firstPage = (PageBox) pages.get(0);
        com.itextpdf.text.Rectangle firstPageSize = new com.itextpdf.text.Rectangle(0, 0, firstPage.getWidth(c) / _dotsPerPoint,
                firstPage.getHeight(c) / _dotsPerPoint);

        _outputDevice.setStartPageNo(_writer.getPageNumber());

        _pdfDoc.setPageSize(firstPageSize);
        _pdfDoc.newPage();

        writePDF(pages, c, firstPageSize, _pdfDoc, _writer);
    }

    public void finishPDF() {
        if (_pdfDoc != null) {
            fireOnClose();
            _pdfDoc.close();
        }
    }

    public void createPDF(OutputStream os, boolean finish) throws DocumentException, IOException {
        createPDF(os, finish, 0, null);
    }

    /**
     * <B>NOTE:</B> Caller is responsible for cleaning up the OutputStream if
     * something goes wrong.
     * 
     * @throws IOException
     */
    public void createPDF(OutputStream os, boolean finish, int initialPageNo, String language) throws DocumentException, IOException {
        List pages = _root.getLayer().getPages();

        RenderingContext c = newRenderingContext();
        c.setInitialPageNo(initialPageNo);
        PageBox firstPage = (PageBox) pages.get(0);
        com.itextpdf.text.Rectangle firstPageSize = new com.itextpdf.text.Rectangle(0, 0, firstPage.getWidth(c) / _dotsPerPoint,
                firstPage.getHeight(c) / _dotsPerPoint);

        com.itextpdf.text.Document doc = new com.itextpdf.text.Document(firstPageSize, 0, 0, 0, 0);
        //PDF/UA
//        PdfAWriter writer = PdfAWriter.getInstance(doc, os, PdfAConformanceLevel.PDF_A_1A);      
//        PdfWriter writer = PdfWriter.getInstance(doc, os);
        DocListener docTaggedListener = new DocTagListenerAccessibleImpl();
        // PDF/UA the listener is setted in PDFWriter and will be called on newPage
        PdfWriter writer = PdfWriter.getInstance(doc, os, docTaggedListener);
        if (_pdfVersion != null) {
            writer.setPdfVersion(_pdfVersion.charValue());
        }
        if (_pdfEncryption != null) {
            writer.setEncryption(_pdfEncryption.getUserPassword(), _pdfEncryption.getOwnerPassword(),
                    _pdfEncryption.getAllowedPrivileges(), _pdfEncryption.getEncryptionType());
        }
        
        //PDF/UA
        ITextRendererAccessible.addAccessibilityMetaData(writer, doc, language, null);
        //PDF/UA End
        
        _pdfDoc = doc;
        _writer = writer;

        firePreOpen();
        doc.open();

        //PDF/UA The listener is setted on ITextOutputDevice and will be called on begin tag and entag method of PDFContentByte
        writePDF(pages, c, firstPageSize, doc, writer, docTaggedListener);

        if (finish) {
            fireOnClose();
            doc.close();
        }
    }

    private void firePreOpen() {
        if (_listener != null) {
            _listener.preOpen(this);
        }
    }

    private void firePreWrite(int pageCount) {
        if (_listener != null) {
            _listener.preWrite(this, pageCount);
        }
    }

    private void fireOnClose() {
        if (_listener != null) {
            _listener.onClose(this);
        }
    }
    
    private void writePDF(List pages, RenderingContext c, com.itextpdf.text.Rectangle firstPageSize, com.itextpdf.text.Document doc,
            PdfWriter writer) throws DocumentException, IOException {
    	writePDF(pages, c, firstPageSize, doc, writer, null);
    }

  //PDF/UA: New param PDFCreationListener for setting it on ITextOuputDevice 
    private void writePDF(List pages, RenderingContext c, com.itextpdf.text.Rectangle firstPageSize, com.itextpdf.text.Document doc,
            PdfWriter writer, DocListener listener) throws DocumentException, IOException {
        _outputDevice.setRoot(_root);

        _outputDevice.start(_doc);
        //PDF/UA: setting rendering context and DocTagListener on begin Tag and end tag of PDFContentByte
        _outputDevice.setRenderingContext(c);
        if(listener instanceof DocTagListenerAccessible){
        	_outputDevice.setListener((DocTagListenerAccessible)listener);
        }
        _outputDevice.setWriter(writer);
        _outputDevice.initializePage(writer.getDirectContent(), firstPageSize.getHeight());

        _root.getLayer().assignPagePaintingPositions(c, Layer.PAGED_MODE_PRINT);

        int pageCount = _root.getLayer().getPages().size();
        c.setPageCount(pageCount);
        firePreWrite(pageCount); // opportunity to adjust meta data
        setDidValues(doc); // set PDF header fields from meta data
        for (int i = 0; i < pageCount; i++) {
            PageBox currentPage = (PageBox) pages.get(i);
            c.setPage(i, currentPage);
            paintPage(c, writer, currentPage);
            _outputDevice.finishPage();
            if (i != pageCount - 1) {
                PageBox nextPage = (PageBox) pages.get(i + 1);
                com.itextpdf.text.Rectangle nextPageSize = new com.itextpdf.text.Rectangle(0, 0, nextPage.getWidth(c) / _dotsPerPoint,
                        nextPage.getHeight(c) / _dotsPerPoint);
                doc.setPageSize(nextPageSize);
                // PDF/UA usar un PDFACreationListener para controlar cuando se crea una nueva pagina, ya que hay cerrar
                try{
                	doc.newPage();
                }catch(IllegalPdfSyntaxException e){
                	//PDF/UA iText bug counting opening and closed tags, try avoid unbalanced document exceptions closing one tag.
                	ITextOutputDeviceAccessibleUtil.endMarkedContentSequence(writer.getDirectContent(), (DocTagListenerAccessible)listener);
                	System.out.println("IllegalPdfSyntaxException: endedMArkedContentSecuence");
                }
                _outputDevice.initializePage(writer.getDirectContent(), nextPageSize.getHeight());
            }
        }

        _outputDevice.finish(c, _root);
    }

    // Sets the document information dictionary values from html metadata
    private void setDidValues(com.itextpdf.text.Document doc) {
        String v = _outputDevice.getMetadataByName("title");
        if (v != null) {
            doc.addTitle(v);
        }
        v = _outputDevice.getMetadataByName("author");
        if (v != null) {
            doc.addAuthor(v);
        }
        v = _outputDevice.getMetadataByName("subject");
        if (v != null) {
            doc.addSubject(v);
        }
        v = _outputDevice.getMetadataByName("keywords");
        if (v != null) {
            doc.addKeywords(v);
        }
    }

    private void paintPage(RenderingContext c, PdfWriter writer, PageBox page) throws IOException {
        provideMetadataToPage(writer, page);

        page.paintBackground(c, 0, Layer.PAGED_MODE_PRINT);
        page.paintMarginAreas(c, 0, Layer.PAGED_MODE_PRINT);
        page.paintBorder(c, 0, Layer.PAGED_MODE_PRINT);

        Shape working = _outputDevice.getClip();

        Rectangle content = page.getPrintClippingBounds(c);
        _outputDevice.clip(content);

        int top = -page.getPaintingTop() + page.getMarginBorderPadding(c, CalculatedStyle.TOP);

        int left = page.getMarginBorderPadding(c, CalculatedStyle.LEFT);

        _outputDevice.translate(left, top);
        _root.getLayer().paint(c);
        _outputDevice.translate(-left, -top);

        _outputDevice.setClip(working);
    }

    private void provideMetadataToPage(PdfWriter writer, PageBox page) throws IOException {
        byte[] metadata = null;
        if (page.getMetadata() != null) {
            try {
                String metadataBody = stringfyMetadata(page.getMetadata());
                if (metadataBody != null) {
                    metadata = createXPacket(stringfyMetadata(page.getMetadata())).getBytes("UTF-8");
                }
            } catch (UnsupportedEncodingException e) {
                // Can't happen
                throw new RuntimeException(e);
            }
        }

        if (metadata != null) {
            writer.setPageXmpMetadata(metadata);
        }
    }

    private String stringfyMetadata(Element element) {
        Element target = getFirstChildElement(element);
        if (target == null) {
            return null;
        }

        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(target), new StreamResult(output));

            return output.toString();
        } catch (TransformerConfigurationException e) {
            // Things must be in pretty bad shape to get here so
            // rethrow as runtime exception
            throw new RuntimeException(e);
        } catch (TransformerException e) {
            throw new RuntimeException(e);
        }
    }

    private static Element getFirstChildElement(Element element) {
        Node n = element.getFirstChild();
        while (n != null) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) n;
            }
            n = n.getNextSibling();
        }
        return null;
    }

    private String createXPacket(String metadata) {
        StringBuffer result = new StringBuffer(metadata.length() + 50);
        result.append("<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>\n");
        result.append(metadata);
        result.append("\n<?xpacket end='r'?>");

        return result.toString();
    }

    public ITextOutputDevice getOutputDevice() {
        return _outputDevice;
    }

    public SharedContext getSharedContext() {
        return _sharedContext;
    }

    public void exportText(Writer writer) throws IOException {
        RenderingContext c = newRenderingContext();
        c.setPageCount(_root.getLayer().getPages().size());
        _root.exportText(c, writer);
    }

    public BlockBox getRootBox() {
        return _root;
    }

    public float getDotsPerPoint() {
        return _dotsPerPoint;
    }

    public List findPagePositionsByID(Pattern pattern) {
        return _outputDevice.findPagePositionsByID(newLayoutContext(), pattern);
    }

    private static final class NullUserInterface implements UserInterface {
        public boolean isHover(Element e) {
            return false;
        }

        public boolean isActive(Element e) {
            return false;
        }

        public boolean isFocus(Element e) {
            return false;
        }
    }

    public PDFCreationListener getListener() {
        return _listener;
    }

    public void setListener(PDFCreationListener listener) {
        _listener = listener;
    }

    public PdfWriter getWriter() {
        return _writer;
    }
}
