package com.api.product.core.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XML helpers for SOAP/XML responses: namespace-agnostic XPath (write /Envelope/Body/AddResponse/AddResult and it matches whatever
 * prefixes the service uses), SOAP Fault detection and a readable fault message. Parsing never resolves DTDs or external entities.
 */
public final class XmlLite {

    private static final Pattern FAULT = Pattern.compile("(?is)<(?:[A-Za-z0-9_]+:)?Fault\\b");
    private static final Pattern FAULT_STRING = Pattern.compile("(?is)<(?:[A-Za-z0-9_]+:)?(?:faultstring|Text|Reason)\\b[^>]*>(.*?)</");

    private XmlLite() {}

    public static boolean looksLikeXml(String s) {
        return s != null && s.stripLeading().startsWith("<");
    }

    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** First match of the expression as text, or null when nothing matches or the body is not XML. */
    public static String xpath(String xml, String expr) {
        if (!looksLikeXml(xml)) return null;
        Document d = parse(xml);
        if (d == null) return null;
        try {
            XPath xp = XPathFactory.newInstance().newXPath();
            NodeList nl = (NodeList) xp.evaluate(rewrite(expr), d, XPathConstants.NODESET);
            if (nl.getLength() == 0) return null;
            Node n = nl.item(0);
            String t = n.getTextContent();
            return t == null ? "" : t.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** SOAP Fault text (faultstring / Reason), "" for a fault without text, or null when the body is not a fault. */
    public static String fault(String body) {
        if (!looksLikeXml(body) || !FAULT.matcher(body).find()) return null;
        Matcher m = FAULT_STRING.matcher(body);
        String t = m.find() ? m.group(1).replaceAll("<[^>]+>", "").trim() : "";
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    /** Rewrites name steps to local-name() tests so prefixes and default namespaces do not matter. */
    static String rewrite(String expr) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        String e = expr.trim();
        while (i < e.length()) {
            char c = e.charAt(i);
            if (c == '/' || c == '(' || c == ')' || c == '|' || c == '[' || c == ']' || c == ' ' || c == '=' || c == '\'' || c == '"' || c == '@' || c == '.' || c == '*' || Character.isDigit(c)) {
                if (c == '\'' || c == '"') {
                    int j = e.indexOf(c, i + 1);
                    j = j < 0 ? e.length() - 1 : j;
                    out.append(e, i, j + 1);
                    i = j + 1;
                    continue;
                }
                if (c == '@') {
                    int j = i + 1;
                    while (j < e.length() && (Character.isLetterOrDigit(e.charAt(j)) || "_-.:".indexOf(e.charAt(j)) >= 0)) j++;
                    out.append("@*[local-name()='").append(e, i + 1, j).append("']");
                    i = j;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < e.length() && (Character.isLetterOrDigit(e.charAt(j)) || "_-.:".indexOf(e.charAt(j)) >= 0)) j++;
            String name = e.substring(i, j);
            boolean fn = j < e.length() && e.charAt(j) == '(';
            if (name.isEmpty()) {
                out.append(c);
                i++;
            } else if (fn || name.contains("::") || name.equals("and") || name.equals("or")) {
                out.append(name);
                i = j;
            } else {
                String local = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
                out.append("*[local-name()='").append(local).append("']");
                i = j;
            }
        }
        return out.toString();
    }
}
