package com.api.product.core.importer;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Assertion;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.Environment;
import com.api.product.core.model.ApiModel.TestCase;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * WSDL 1.1 (SOAP 1.1 and 1.2, document/literal and rpc/literal) to a project. Each operation becomes a POST endpoint with a sample
 * SOAP envelope built from the schema, the SOAPAction header and a valid-request test that expects no SOAP Fault. Parsing never loads
 * DTDs or external entities. Schemas that are only referenced (xsd:import of another file/URL) are reported, not fetched.
 */
public final class WsdlImporter {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema";
    private static final String WSDL = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP11 = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String SOAP12 = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private static final String ENV11 = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String ENV12 = "http://www.w3.org/2003/05/soap-envelope";

    private final ApiModel model = new ApiModel();
    private Document doc;
    private String tns = "";
    private final Map<String, Element> elements = new HashMap<>();
    private final Map<String, Element> complexTypes = new HashMap<>();
    private final Map<String, Element> simpleTypes = new HashMap<>();
    private final Map<String, String> schemaNs = new HashMap<>();   // global element/type name -> its schema target namespace
    private final Map<String, Boolean> qualified = new HashMap<>();   // schema target namespace -> elementFormDefault qualified
    private final Map<String, Element> messages = new HashMap<>();
    private final Map<String, Element> portTypes = new HashMap<>();

    private WsdlImporter() {}

    public static boolean looksLikeWsdl(String text) {
        return text != null && text.contains("definitions") && text.contains("schemas.xmlsoap.org/wsdl");
    }

    public static ApiModel importFile(Path file, String fileName) throws Exception {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        WsdlImporter w = new WsdlImporter();
        return w.build(text, fileName);
    }

    private ApiModel build(String text, String fileName) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        try {
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("This is not a valid WSDL file: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        if (!"definitions".equals(root.getLocalName()) || !WSDL.equals(root.getNamespaceURI())) throw new IllegalArgumentException("This XML is not a WSDL 1.1 document (expected wsdl:definitions)");
        tns = root.getAttribute("targetNamespace");
        model.api.name = root.getAttribute("name").isEmpty() ? fileName.replaceAll("\\.[^.]+$", "") : root.getAttribute("name");
        model.api.source.type = "wsdl";
        model.api.source.ref = fileName;
        model.api.contractMode = "off";

        for (Element schema : children(first(root, "types"), XSD, "schema")) collectSchema(schema);
        for (Element m : children(root, WSDL, "message")) messages.put(m.getAttribute("name"), m);
        for (Element pt : children(root, WSDL, "portType")) portTypes.put(pt.getAttribute("name"), pt);
        Map<String, Element> bindings = new LinkedHashMap<>();
        for (Element b : children(root, WSDL, "binding")) bindings.put(b.getAttribute("name"), b);

        Environment env = new Environment();
        env.name = "DEFAULT";
        model.environments.add(env);
        Set<String> doneBindings = new HashSet<>();
        Map<String, Integer> ids = new HashMap<>();
        List<Element[]> ports = new ArrayList<>();   // {service, port}
        for (Element svc : children(root, WSDL, "service")) for (Element port : children(svc, WSDL, "port")) ports.add(new Element[] { svc, port });
        // prefer SOAP 1.1 when a service offers both versions of the same binding
        ports.sort((a, b) -> Boolean.compare(is12(a[1]), is12(b[1])));
        for (Element[] sp : ports) {
            Element port = sp[1];
            Element addr = firstAny(port, "address");
            if (addr == null) continue;
            String binding = local(port.getAttribute("binding"));
            Element b = bindings.get(binding);
            if (b == null) continue;
            String ptName = local(b.getAttribute("type"));
            if (!doneBindings.add(ptName)) continue;
            String loc = addr.getAttribute("location");
            URI u = URI.create(loc);
            String origin = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            if (env.baseUrl == null) env.baseUrl = origin;
            else if (!env.baseUrl.equals(origin)) model.api.source.warnings.add("Service port " + port.getAttribute("name") + " is on another host (" + origin + "); only " + env.baseUrl + " is used.");
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            boolean v12 = is12(port);
            Element pt = portTypes.get(ptName);
            if (pt == null) continue;
            String bindStyle = firstAny(b, "binding") == null ? "document" : firstAny(b, "binding").getAttribute("style");
            for (Element op : children(b, WSDL, "operation")) {
                String opName = op.getAttribute("name");
                Element sop = firstAny(op, "operation");
                String action = sop == null ? "" : sop.getAttribute("soapAction");
                String style = sop != null && !sop.getAttribute("style").isEmpty() ? sop.getAttribute("style") : bindStyle.isEmpty() ? "document" : bindStyle;
                Element in = first(op, "input");
                Element body = in == null ? null : firstAny(in, "body");
                if (body != null && "encoded".equals(body.getAttribute("use"))) model.api.source.warnings.add(opName + ": SOAP encoded use is not supported; sample may be inaccurate.");
                Element ptOp = null;
                for (Element o : children(pt, WSDL, "operation")) if (opName.equals(o.getAttribute("name"))) ptOp = o;
                if (ptOp == null) continue;
                Element ptIn = first(ptOp, "input");
                Element msg = ptIn == null ? null : messages.get(local(ptIn.getAttribute("message")));
                String bodyNs = body != null && !body.getAttribute("namespace").isEmpty() ? body.getAttribute("namespace") : tns;
                String inner = bodyXml(opName, msg, style, bodyNs);
                String envNs = v12 ? ENV12 : ENV11;
                String envelope = "<soapenv:Envelope xmlns:soapenv=\"" + envNs + "\"><soapenv:Header/><soapenv:Body>" + inner + "</soapenv:Body></soapenv:Envelope>";

                Endpoint ep = new Endpoint();
                String base = opName.replaceAll("[^A-Za-z0-9_]+", "_");
                int n = ids.merge(base, 1, Integer::sum);
                ep.id = n == 1 ? base : base + "_" + n;
                ep.name = opName + " (SOAP " + (v12 ? "1.2" : "1.1") + ")";
                ep.module = pt.getAttribute("name").isEmpty() ? sp[0].getAttribute("name") : pt.getAttribute("name");
                ep.method = "POST";
                ep.path = path;
                ep.contentType = v12 ? "application/soap+xml; charset=utf-8" + (action.isEmpty() ? "" : "; action=\"" + action + "\"") : "text/xml; charset=utf-8";
                if (!v12) ep.headers.put("SOAPAction", "\"" + action + "\"");
                ep.requestBody = JsonNodeFactory.instance.textNode(envelope);
                ep.idempotent = opName.matches("(?i)^(get|find|search|list|query|check|read|fetch|is|has|view).*");
                model.endpoints.add(ep);

                TestCase t = new TestCase();
                t.id = "TC_" + ep.id + "_POSITIVE";
                t.endpointId = ep.id;
                t.category = "POSITIVE";
                t.title = ep.name + " - valid request";
                t.lifecycle = "DRAFT";
                t.origin = "generated";
                t.expected.status.add(200);
                Assertion a = new Assertion();
                a.type = "soapNoFault";
                t.expected.assertions.add(a);
                model.testCases.add(t);
            }
        }
        if (env.baseUrl == null) {
            env.baseUrl = "";
            model.api.source.warnings.add("No service address found in the WSDL; enter the application URL.");
        }
        if (model.endpoints.isEmpty()) model.api.source.warnings.add("No SOAP operations were found (WSDL needs service/port, binding and portType).");
        return model;
    }

    // ------------------------------------------------------------------ schema

    private void collectSchema(Element schema) {
        String ns = schema.getAttribute("targetNamespace");
        qualified.put(ns, "qualified".equals(schema.getAttribute("elementFormDefault")));
        for (Element e : children(schema, XSD, "element")) {
            elements.put(e.getAttribute("name"), e);
            schemaNs.put("e:" + e.getAttribute("name"), ns);
        }
        for (Element c : children(schema, XSD, "complexType")) {
            complexTypes.put(c.getAttribute("name"), c);
            schemaNs.put("t:" + c.getAttribute("name"), ns);
        }
        for (Element s : children(schema, XSD, "simpleType")) simpleTypes.put(s.getAttribute("name"), s);
        for (Element imp : children(schema, XSD, "import")) {
            if (!imp.getAttribute("schemaLocation").isEmpty()) model.api.source.warnings.add("Schema import '" + imp.getAttribute("schemaLocation") + "' is not fetched; sample data for its types may be incomplete.");
        }
        for (Element imp : children(schema, XSD, "include")) {
            model.api.source.warnings.add("Schema include '" + imp.getAttribute("schemaLocation") + "' is not fetched; sample data for its types may be incomplete.");
        }
    }

    private String bodyXml(String op, Element msg, String style, String bodyNs) {
        StringBuilder sb = new StringBuilder();
        if (msg == null) return "<" + op + " xmlns=\"" + bodyNs + "\"/>";
        List<Element> parts = children(msg, WSDL, "part");
        if ("rpc".equals(style)) {
            sb.append("<ns:").append(op).append(" xmlns:ns=\"").append(esc(bodyNs)).append("\">");
            for (Element p : parts) {
                sb.append("<").append(p.getAttribute("name")).append(">");
                if (!p.getAttribute("type").isEmpty()) sb.append(sampleType(local(p.getAttribute("type")), 0, new HashSet<>(), true));
                sb.append("</").append(p.getAttribute("name")).append(">");
            }
            return sb.append("</ns:").append(op).append(">").toString();
        }
        for (Element p : parts) {
            if (!p.getAttribute("element").isEmpty()) {
                String name = local(p.getAttribute("element"));
                Element el = elements.get(name);
                String ens = schemaNs.getOrDefault("e:" + name, tns);
                sb.append(elementXml(el, name, ens, true, 0, new HashSet<>(), true));
            } else if (!p.getAttribute("type").isEmpty()) {
                sb.append("<").append(p.getAttribute("name")).append(">").append(sampleType(local(p.getAttribute("type")), 0, new HashSet<>(), true)).append("</").append(p.getAttribute("name")).append(">");
            }
        }
        return sb.toString();
    }

    /** A global or local element as XML text with sample content. {@code top} adds the namespace declaration. */
    private String elementXml(Element el, String name, String ns, boolean top, int depth, Set<String> guard, boolean parentQualified) {
        String ref = el == null ? "" : el.getAttribute("ref");
        if (!ref.isEmpty()) {
            Element target = elements.get(local(ref));
            return elementXml(target, local(ref), schemaNs.getOrDefault("e:" + local(ref), ns), false, depth, guard, true);
        }
        String attrNs = top ? " xmlns=\"" + esc(ns) + "\"" : (parentQualified ? "" : "");
        StringBuilder sb = new StringBuilder("<").append(name).append(attrNs);
        String content;
        Element complex = el == null ? null : firstChild(el, XSD, "complexType");
        Element simple = el == null ? null : firstChild(el, XSD, "simpleType");
        String type = el == null ? "" : el.getAttribute("type");
        boolean q = qualified.getOrDefault(ns, false);
        if (complex != null) {
            content = complexXml(complex, ns, depth + 1, guard, q);
        } else if (!type.isEmpty() && complexTypes.containsKey(local(type))) {
            String key = "t:" + local(type);
            if (!guard.add(key) || depth > 6) {
                return sb.append("/>").toString();
            }
            content = complexXml(complexTypes.get(local(type)), schemaNs.getOrDefault(key, ns), depth + 1, guard, qualified.getOrDefault(schemaNs.getOrDefault(key, ns), q));
            guard.remove(key);
        } else if (simple != null) {
            content = simpleValue(simple);
        } else if (!type.isEmpty()) {
            content = sampleType(local(type), depth, guard, false);
        } else {
            content = "";
        }
        if (!top && !q && depth > 0) {
            // unqualified local element: it must not inherit the default namespace
            sb.append(" xmlns=\"\"");
        }
        return sb.append(">").append(content).append("</").append(name).append(">").toString();
    }

    private String complexXml(Element ct, String ns, int depth, Set<String> guard, boolean q) {
        if (depth > 6) return "";
        StringBuilder sb = new StringBuilder();
        for (Element c : childElements(ct)) {
            String ln = c.getLocalName();
            if (!XSD.equals(c.getNamespaceURI())) continue;
            switch (ln) {
                case "sequence":
                case "all":
                    sb.append(group(c, ns, depth, guard, q, false));
                    break;
                case "choice":
                    sb.append(group(c, ns, depth, guard, q, true));
                    break;
                case "complexContent": {
                    Element ext = firstChild(c, XSD, "extension");
                    if (ext != null) {
                        Element base = complexTypes.get(local(ext.getAttribute("base")));
                        if (base != null && guard.add("t:" + local(ext.getAttribute("base")))) {
                            sb.append(complexXml(base, ns, depth + 1, guard, q));
                            guard.remove("t:" + local(ext.getAttribute("base")));
                        }
                        sb.append(complexXml(ext, ns, depth + 1, guard, q));
                    }
                    break;
                }
                case "simpleContent": {
                    Element ext = firstChild(c, XSD, "extension");
                    sb.append(ext == null ? "x" : sampleType(local(ext.getAttribute("base")), depth, guard, false));
                    break;
                }
                default:
                    break;
            }
        }
        return sb.toString();
    }

    private String group(Element g, String ns, int depth, Set<String> guard, boolean q, boolean firstOnly) {
        StringBuilder sb = new StringBuilder();
        for (Element c : childElements(g)) {
            if (!XSD.equals(c.getNamespaceURI())) continue;
            if (c.getLocalName().equals("element")) {
                String name = c.getAttribute("name").isEmpty() ? local(c.getAttribute("ref")) : c.getAttribute("name");
                if (name.isEmpty()) continue;
                sb.append(elementXml(c, name, ns, false, depth, guard, q));
                if (firstOnly) break;
            } else if (c.getLocalName().equals("sequence") || c.getLocalName().equals("choice") || c.getLocalName().equals("all")) {
                sb.append(group(c, ns, depth + 1, guard, q, c.getLocalName().equals("choice")));
                if (firstOnly) break;
            }
        }
        return sb.toString();
    }

    private String sampleType(String t, int depth, Set<String> guard, boolean topLevel) {
        if (complexTypes.containsKey(t)) {
            String key = "t:" + t;
            if (!guard.add(key) || depth > 6) return "";
            String r = complexXml(complexTypes.get(t), schemaNs.getOrDefault(key, tns), depth + 1, guard, qualified.getOrDefault(schemaNs.getOrDefault(key, tns), false));
            guard.remove(key);
            return r;
        }
        if (simpleTypes.containsKey(t)) return simpleValue(simpleTypes.get(t));
        switch (t.toLowerCase(Locale.ROOT)) {
            case "int": case "integer": case "long": case "short": case "byte": case "unsignedint": case "unsignedlong": case "unsignedshort": case "nonnegativeinteger": case "positiveinteger": return "1";
            case "decimal": case "double": case "float": return "1.5";
            case "boolean": return "true";
            case "date": return "2025-01-15";
            case "datetime": return "2025-01-15T10:00:00";
            case "time": return "10:00:00";
            case "base64binary": return "QQ==";
            case "anytype": case "anysimpletype": return "sample";
            default: return "string";
        }
    }

    private String simpleValue(Element st) {
        Element r = firstChild(st, XSD, "restriction");
        if (r == null) return "string";
        for (Element e : childElements(r)) if ("enumeration".equals(e.getLocalName())) return esc(e.getAttribute("value"));
        return sampleType(local(r.getAttribute("base")), 0, new HashSet<>(), false);
    }

    // ------------------------------------------------------------------ DOM helpers

    private static boolean is12(Element port) {
        for (Element c : childElements(port)) if (SOAP12.equals(c.getNamespaceURI())) return true;
        return false;
    }

    private static String local(String qname) {
        int i = qname == null ? -1 : qname.indexOf(':');
        return i < 0 ? (qname == null ? "" : qname) : qname.substring(i + 1);
    }

    private static List<Element> childElements(Node n) {
        List<Element> out = new ArrayList<>();
        if (n == null) return out;
        NodeList nl = n.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element) out.add((Element) nl.item(i));
        return out;
    }

    private static List<Element> children(Node n, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Element e : childElements(n)) if (local.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) out.add(e);
        return out;
    }

    private static Element first(Node n, String local) {
        List<Element> l = children(n, WSDL, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static Element firstChild(Node n, String ns, String local) {
        List<Element> l = children(n, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    /** First child with this local name in the SOAP 1.1 or 1.2 binding namespace. */
    private static Element firstAny(Node n, String local) {
        for (Element e : childElements(n)) if (local.equals(e.getLocalName()) && (SOAP11.equals(e.getNamespaceURI()) || SOAP12.equals(e.getNamespaceURI()))) return e;
        return null;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
