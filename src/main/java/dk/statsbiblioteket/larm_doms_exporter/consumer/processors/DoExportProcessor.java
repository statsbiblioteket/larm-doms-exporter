package dk.statsbiblioteket.larm_doms_exporter.consumer.processors;

import dk.statsbiblioteket.doms.central.CentralWebservice;
import dk.statsbiblioteket.doms.central.InvalidCredentialsException;
import dk.statsbiblioteket.doms.central.InvalidResourceException;
import dk.statsbiblioteket.doms.central.MethodFailedException;
import dk.statsbiblioteket.doms.central.Relation;
import dk.statsbiblioteket.larm_doms_exporter.cli.ExportContext;
import dk.statsbiblioteket.larm_doms_exporter.consumer.ExportRequestState;
import dk.statsbiblioteket.larm_doms_exporter.consumer.ProcessorChainElement;
import dk.statsbiblioteket.larm_doms_exporter.consumer.ProcessorException;
import dk.statsbiblioteket.larm_doms_exporter.persistence.DomsExportRecord;
import dk.statsbiblioteket.larm_doms_exporter.util.ChannelMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 *  Compare with the following snippet from the old exporter:
 * .append("    <doms_id>"              + radioProgramMetadata.shardPid                                              + "</doms_id>\n")
 					.append("    <channel_name>"         + radioProgramMetadata.getPbcoreProgramMetadata().channel                    + "</channel_name>\n")
 					.append("    <title>"                + radioProgramMetadata.getPbcoreProgramMetadata().titel                      + "</title>\n")
 					.append("    <title_original>"       + radioProgramMetadata.getPbcoreProgramMetadata().originaltitel              + "</title_original>\n")
 					.append("    <title_episode>"        + radioProgramMetadata.getPbcoreProgramMetadata().episodetitel               + "</title_episode>\n")
 					.append("    <start_time>"           + fmt.print(radioProgramMetadata.getPbcoreProgramMetadata().start.getTime()) + "</start_time>\n")
 					.append("    <end_time>"             + fmt.print(radioProgramMetadata.getPbcoreProgramMetadata().end.getTime())   + "</end_time>\n")
 					.append("    <description_short>"    + radioProgramMetadata.getPbcoreProgramMetadata().descriptionKortOmtale      + "</description_short>\n")
 					.append("    <description_long1>"    + radioProgramMetadata.getPbcoreProgramMetadata().descriptionLangOmtale1     + "</description_long1>\n")
 					.append("    <description_long2>"    + radioProgramMetadata.getPbcoreProgramMetadata().descriptionLangOmtale2     + "</description_long2>\n")
 					.append("    <creator>"              + radioProgramMetadata.getPbcoreProgramMetadata().forfattere                 + "</creator>\n")
 					.append("    <contributor>"          + radioProgramMetadata.getPbcoreProgramMetadata().medvirkende                + "</contributor>\n")
 					.append("    <contributor_director>" + radioProgramMetadata.getPbcoreProgramMetadata().instruktion                + "</contributor_director>\n")
 */
public class DoExportProcessor extends ProcessorChainElement {

    private static Logger logger = LoggerFactory.getLogger(DoExportProcessor.class);
    public static String XML_TEMPLATE = null;
    public static final String HAS_SHARD = "http://doms.statsbiblioteket.dk/relations/default/0/1/#hasShard";
    private static final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    SimpleDateFormat domsDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    SimpleDateFormat chaosDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");


    static {
        InputStream is = ClassLoader.class.getResourceAsStream("/CHAOS_envelope_template.xml");
        try {
            XML_TEMPLATE = IOUtils.toString(is, "UTF-8");
            logger.debug("Read XML template: " + XML_TEMPLATE);
        } catch (IOException e) {
            throw new RuntimeException("Problem reading XML template file", e);
        }
    }

    public static class PBCoreNamespaceResolver implements NamespaceContext {

           @Override
           public String getNamespaceURI(String prefix) {
               if (prefix.equals("pbcore")) return  "http://www.pbcore.org/PBCore/PBCoreNamespace.html";
               else   return XMLConstants.NULL_NS_URI;
           }

           @Override
           public String getPrefix(String namespaceURI) {
               throw new RuntimeException("Not yet implemented");

           }

           @Override
           public Iterator getPrefixes(String namespaceURI) {
               throw new RuntimeException("Not yet implemented");

           }
       }

    private static XPathFactory xpathFactory = XPathFactory.newInstance();
    private static NamespaceContext pbcoreNamespaceContext = new PBCoreNamespaceResolver();




    @Override
    protected void processThis(DomsExportRecord record, ExportContext context, ExportRequestState state) throws ProcessorException {
        File outputDir = context.getOutputDirectory();
        File tempOutputDir = new File(outputDir, "tempDir");
        tempOutputDir.mkdirs();
        File outputFile = new File(outputDir, record.getID().replaceAll("uuid:","")+".xml");
        File tempOutputFile = new File(tempOutputDir, outputFile.getName());
        String result = XML_TEMPLATE;
        org.w3c.dom.Document pbcoreDocument;
        dbf.setNamespaceAware(true);
        try {
            DocumentBuilder builder = dbf.newDocumentBuilder();
            pbcoreDocument = builder.parse(new ByteArrayInputStream(state.getPbcoreString().getBytes()));
            state.setPbcoreDocument(pbcoreDocument);
        } catch (Exception e) {
            throw new ProcessorException("Error parsing pbcore", e);
        }
        try {
            result = substituteShardPid(result, record, context, state);
            result = substituteEndTimeString(result, record, context, state);
            result = substituteStartTimeString(result, record, context, state);
            result = substituteChannel(result, record, context, state);
            result = substituteGeneric(result, record, context, state, "###TITLE###","/pbcore:PBCoreDescriptionDocument/pbcore:pbcoreTitle[pbcore:titleType='titel']/pbcore:title");
            result = substituteGeneric(result, record, context, state, "###ABSTRACT###","/pbcore:PBCoreDescriptionDocument/pbcore:pbcoreDescription[pbcore:descriptionType='kortomtale']/pbcore:description");
            result = substituteGeneric(result, record, context, state, "###DESCRIPTION1###","/pbcore:PBCoreDescriptionDocument/pbcore:pbcoreDescription[pbcore:descriptionType='langomtale1']/pbcore:description");
            result = substituteGeneric(result, record, context, state, "###DESCRIPTION2###","/pbcore:PBCoreDescriptionDocument/pbcore:pbcoreDescription[pbcore:descriptionType='langomtale2']/pbcore:description");
            result = substitutePublisher(result, record, context, state);
            result = substituteLogoFilename(result, record, context, state);
            result = substituteEmpty(result, "###CREATOR###");
            result = substituteEmpty(result, "###LOCATIONS###");
            result = substituteProgramPid(result, record, context, state);
            result = substituteFilename(result, record, context, state);
        } catch (Exception e) {
            throw new ProcessorException("Error getting start time", e);
        }
        logger.info("Writing new export file " + outputFile.getAbsolutePath());
        try {
            FileOutputStream os = new FileOutputStream(tempOutputFile);
            os.write(result.getBytes());
            os.flush();
            os.close();
        } catch (IOException e) {
            throw new ProcessorException("Could not write output file");
        }
        tempOutputFile.renameTo(outputFile);
    }

    private String substituteShardPid(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) throws ProcessorException {
        String patternString = "###SHARD_ID###";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        CentralWebservice domsWS = context.getDomsCentralWebservice();
        try {
            List<Relation> relations = domsWS.getNamedRelations(record.getID(), HAS_SHARD);
            String shardPid = "";
            if (relations.size() == 1) {
                shardPid = relations.get(0).getObject().replace("uuid:","");
            }
            return pattern.matcher(template).replaceAll(shardPid);
        } catch (Exception e) {
            throw new ProcessorException("Could not get hasShard relation", e);
        }
    }

    private String substituteStartTimeString(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) throws XPathExpressionException, ParseException {
        String patternString = "###START_TIME###";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        String xpathString = "//pbcore:dateAvailableStart";
        XPath xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(pbcoreNamespaceContext);
        String programStartString = (String) xpath.evaluate(xpathString, state.getPbcoreDocument(), XPathConstants.STRING);
        String chaosStartString = chaosDateFormat.format(domsDateFormat.parse(programStartString));
        return pattern.matcher(template).replaceAll(chaosStartString);
    }

    private String substituteEndTimeString(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) throws XPathExpressionException, ParseException {
        String patternString = "###END_TIME###";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        String xpathString = "//pbcore:dateAvailableEnd";
        XPath xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(pbcoreNamespaceContext);
        String programStartString = (String) xpath.evaluate(xpathString, state.getPbcoreDocument(), XPathConstants.STRING);
        String chaosStartString = chaosDateFormat.format(domsDateFormat.parse(programStartString));
        return pattern.matcher(template).replaceAll(chaosStartString);
    }

    private String substituteChannel(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) throws XPathExpressionException {
        String patternString = "###CHANNEL###";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        String xpathString = "/pbcore:PBCoreDescriptionDocument/pbcore:pbcorePublisher[pbcore:publisherRole='channel_name']/pbcore:publisher";
        XPath xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(pbcoreNamespaceContext);
        String matchingString = (String) xpath.evaluate(xpathString, state.getPbcoreDocument(), XPathConstants.STRING);
        return pattern.matcher(template).replaceAll(ChannelMapper.getChaosChannel(matchingString));
    }

    private String substitutePublisher(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) throws XPathExpressionException {
        String patternString = "###PUBLISHER###";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        String xpathString = "/pbcore:PBCoreDescriptionDocument/pbcore:pbcorePublisher[pbcore:publisherRole='channel_name']/pbcore:publisher";
        XPath xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(pbcoreNamespaceContext);
        String matchingString = (String) xpath.evaluate(xpathString, state.getPbcoreDocument(), XPathConstants.STRING);
        return pattern.matcher(template).replaceAll(ChannelMapper.getPublisher(matchingString));
    }

    private String substituteLogoFilename(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) throws XPathExpressionException {
        String patternString = "###LOGO_FILENAME###";
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        String xpathString = "/pbcore:PBCoreDescriptionDocument/pbcore:pbcorePublisher[pbcore:publisherRole='channel_name']/pbcore:publisher";
        XPath xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(pbcoreNamespaceContext);
        String matchingString = (String) xpath.evaluate(xpathString, state.getPbcoreDocument(), XPathConstants.STRING);
        return pattern.matcher(template).replaceAll(ChannelMapper.getLogoFileName(matchingString));
    }
    private String substituteGeneric(String template, DomsExportRecord record, ExportContext context, ExportRequestState state, String patternString, String xpathString) throws XPathExpressionException {
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        XPath xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(pbcoreNamespaceContext);
        String matchingString = (String) xpath.evaluate(xpathString, state.getPbcoreDocument(), XPathConstants.STRING);
        matchingString = StringEscapeUtils.escapeXml(matchingString);
        return pattern.matcher(template).replaceAll(matchingString);
    }

    private String substituteEmpty(String template, String patternString) {
        Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
        return pattern.matcher(template).replaceAll("");
    }

    private String substituteProgramPid(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) {
        Pattern pattern = Pattern.compile("###PROGRAM_ID###", Pattern.DOTALL);
        return pattern.matcher(template).replaceAll(record.getID().replace("uuid:",""));
    }

    private String substituteFilename(String template, DomsExportRecord record, ExportContext context, ExportRequestState state) {
        Pattern pattern = Pattern.compile("###FILENAME###", Pattern.DOTALL);
        return pattern.matcher(template).replaceAll(record.getID().replace("uuid:","")+".mp3");
    }
}