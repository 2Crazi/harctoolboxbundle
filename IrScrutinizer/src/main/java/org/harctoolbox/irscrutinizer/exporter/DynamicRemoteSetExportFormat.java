/*
Copyright (C) 2013, 2014 Bengt Martensson.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or (at
your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see http://www.gnu.org/licenses/.
*/

package org.harctoolbox.irscrutinizer.exporter;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.harctoolbox.IrpMaster.IrpMasterException;
import org.harctoolbox.IrpMaster.IrpUtils;
import org.harctoolbox.IrpMaster.XmlUtils;
import org.harctoolbox.girr.RemoteSet;
import org.harctoolbox.girr.XmlExporter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * This class is a RemoteSetExporter that dynamically takes its content from the contents of the configuration file exportformats.xml.
 */
public class DynamicRemoteSetExportFormat extends RemoteSetExporter implements IRemoteSetExporter {

    public final static String exportFormatNamespace = "http://www.harctoolbox.org/exportformats";

    private final String formatName;
    private final String extension;
    private final boolean simpleSequence;
    private final boolean binary;
    private final Document xslt;

    private DynamicRemoteSetExportFormat(Element el) {
        super();
        this.formatName = el.getAttribute("name");
        this.extension = el.getAttribute("extension");
        this.simpleSequence = Boolean.parseBoolean(el.getAttribute("simpleSequence"));
        this.binary = Boolean.parseBoolean(el.getAttribute("binary"));

        xslt = XmlUtils.newDocument(true);
        Node stylesheet = el.getElementsByTagNameNS("http://www.w3.org/1999/XSL/Transform", "stylesheet").item(0);
        xslt.appendChild(xslt.importNode(stylesheet, true));
    }

    static HashMap<String, IExporterFactory> parseExportFormats(File file) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(true);
        Document doc = null;
        DocumentBuilder builder = factory.newDocumentBuilder();
        doc = builder.parse(file);

        HashMap<String, IExporterFactory> result = new HashMap<>();
        NodeList nl = doc.getElementsByTagNameNS(exportFormatNamespace, "exportformat");
        for (int i = 0; i < nl.getLength(); i++) {
            final Element el = (Element) nl.item(i);
            final ICommandExporter ef = (el.getAttribute("multiSignal").equals("true"))
                    ? new DynamicRemoteSetExportFormat(el)
                    : new DynamicCommandExportFormat(el);

            result.put(ef.getFormatName(), new IExporterFactory() {

                @Override
                public ICommandExporter newExporter() {
                    return ef;
                }
            });
        }
        return result;
    }

    @Override
    public boolean considersRepetitions() {
        return this.simpleSequence;
    }

    @Override
    public String[][] getFileExtensions() {
        return new String[][] { new String[] { formatName + " files (*." + extension + ")", extension } };
    }

    @Override
    public String getFormatName() {
        return formatName;
    }

    @Override
    public String getPreferredFileExtension() {
        return extension;
    }

    @Override
    public void export(RemoteSet remoteSet, String title, int count, File saveFile, String charsetName) throws IOException, IrpMasterException {
        export(remoteSet, title, count, saveFile.getCanonicalPath(), charsetName);
    }

    private void export(RemoteSet remoteSet, String title, int count, String fileName, String charsetName) throws IOException, IrpMasterException {
        Document document = remoteSet.xmlExportDocument(title,
                null,
                null,
                true, //fatRaw,
                false, // createSchemaLocation,
                true, //generateRaw,
                true, //generateCcf,
                true //generateParameters)
        );
        export(document, fileName, charsetName);
    }

    private void export(Document document, String fileName, String charsetName) throws IOException, IrpMasterException {
        XmlExporter xmlExporter = new XmlExporter(document);
        try (OutputStream out = IrpUtils.getPrintSteam(fileName)) {
            HashMap<String, String> parameters = new HashMap<>(1);
            xmlExporter.printDOM(out, xslt, parameters, binary, charsetName);
        }
    }

    private static void usage(int exitcode) {
        StringBuilder str = new StringBuilder();
        argumentParser.usage(str);

        (exitcode == IrpUtils.exitSuccess ? System.out : System.err).println(str);
        doExit(exitcode); // placifying FindBugs...
    }

    private static void doExit(int exitcode) {
        System.exit(exitcode);
    }

    private static JCommander argumentParser;
    private static CommandLineArgs commandLineArgs = new CommandLineArgs();

    private final static class CommandLineArgs {

        @Parameter(names = {"-c", "--configuration"}, required = true, description = "Pathname of exportformats.xml")
        private String exportFormatsPathname = null;

        @Parameter(names = {"-e", "--encoding"}, description = "Encoding of the generated document")
        private String encoding = "ISO-8859-1";

        @Parameter(names = {"-f", "--format"}, required = true, description = "Name of the desired export format")
        private String formatName = null;

        @Parameter(names = {"-h", "--help", "-?"}, description = "Display help message")
        private boolean helpRequested = false;

        @Parameter(names = {"-n", "--notimes"}, description = "Number of times to repeat a signal (only for CommandExport)")
        private int noTimes = 1;

        @Parameter(names = {"-o", "--outputfile"}, description = "Name of output file")
        private String outputFile = null;

        //@Parameter(names = {"-v", "--verbose"}, description = "Have some commands executed verbosely")
        //private boolean verbose;

        @Parameter(arity = 1, description = "Girr file to be transformed")
        private List<String> parameters = new ArrayList<>();
    }

    public static void main(String[] args) {
        argumentParser = new JCommander(commandLineArgs);
        argumentParser.setProgramName("DynamicRemoteSetExportFormatVersion");

        try {
            argumentParser.parse(args);
        } catch (ParameterException ex) {
            System.err.println(ex.getMessage());
            usage(IrpUtils.exitUsageError);
        }

        if (commandLineArgs.helpRequested)
            usage(IrpUtils.exitSuccess);

        File configFile = new File(commandLineArgs.exportFormatsPathname);
        String formatName = commandLineArgs.formatName;
        File girrFile = new File(commandLineArgs.parameters.get(0));

        try {
            HashMap<String, IExporterFactory> exportFormats = parseExportFormats(configFile);
            //Schema schema = (SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)).newSchema(new URL("http://www.harctoolbox.org/schemas/exportformats.xsd"));

            IExporterFactory format = exportFormats.get(formatName);
            if (format == null) {
                System.err.println("No such export format ``" + formatName + "''");
                System.exit(IrpUtils.exitSemanticUsageError);
            }

            ICommandExporter exporter = format.newExporter();

            String outFileName = commandLineArgs.outputFile != null
                ? commandLineArgs.outputFile
                : girrFile.getCanonicalPath().replaceAll("\\.girr$", "." + exporter.getPreferredFileExtension());
            Document doc = XmlUtils.openXmlFile(girrFile);
            if (DynamicRemoteSetExportFormat.class.isInstance(exporter))
                ((DynamicRemoteSetExportFormat) exporter).export(doc, outFileName, commandLineArgs.encoding);
            else
                ((DynamicCommandExportFormat) exporter).export(doc, outFileName, commandLineArgs.encoding, commandLineArgs.noTimes);
            System.err.println("Created " + outFileName);
        } catch (ParserConfigurationException | SAXException | IOException | IrpMasterException ex) {
            System.err.println(ex + ": " + ex.getMessage());
        }
    }
}
