package com.api.automation.utils;

import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * XML response -> JSON, so the same Extract / Validate rules work for XML (and SOAP) APIs:
 *
 *   <soap:Envelope><soap:Body><Resp status="OK"><cmId>3</cmId><cmId>4</cmId></Resp></soap:Body></soap:Envelope>
 *   -> {"Envelope":{"Body":{"Resp":{"@status":"OK","cmId":["3","4"]}}}}      path: Envelope.Body.Resp.cmId[0]
 *
 * Namespace prefixes are dropped, attributes become "@name", repeated elements become arrays.
 * The parser refuses DOCTYPE / external entities (no XXE while reading a response).
 */
public final class XmlJson {

    private static final ObjectMapper M = new ObjectMapper();

    private XmlJson() {}

    /** JSON text, or null when the text is not well-formed XML. */
    public static String toJson(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            Element root = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
            ObjectNode out = M.createObjectNode();
            out.set(name(root), node(root));
            return M.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    private static String name(Node n) {
        String l = n.getLocalName();
        return l != null ? l : n.getNodeName().replaceAll("^.*:", "");
    }

    private static JsonNode node(Element e) {
        NodeList kids = e.getChildNodes();
        NamedNodeMap attrs = e.getAttributes();
        boolean hasChildElements = false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) hasChildElements = true;
            else if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) text.append(k.getNodeValue());
        }
        int realAttrs = 0;
        for (int i = 0; i < attrs.getLength(); i++) {
            if (!attrs.item(i).getNodeName().startsWith("xmlns")) realAttrs++;
        }
        if (!hasChildElements && realAttrs == 0) return M.getNodeFactory().textNode(text.toString().trim());

        ObjectNode o = M.createObjectNode();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (!a.getNodeName().startsWith("xmlns")) o.put("@" + name(a), a.getNodeValue());
        }
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            String n = name(k);
            JsonNode v = node((Element) k);
            JsonNode existing = o.get(n);
            if (existing == null) o.set(n, v);
            else if (existing.isArray()) ((ArrayNode) existing).add(v);
            else {
                ArrayNode arr = M.createArrayNode();
                arr.add(existing);
                arr.add(v);
                o.set(n, arr);
            }
        }
        String t = text.toString().trim();
        if (!t.isEmpty()) o.put("#text", t);
        return o;
    }
}
