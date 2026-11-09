package com.api.product;

import java.nio.file.Paths;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.ai.AiMemory;
import com.api.product.ai.TestGenerator;
import com.api.product.core.engine.XmlLite;
import com.api.product.core.importer.WsdlImporter;
import com.api.product.core.model.ApiModel;
import com.api.product.core.store.ProjectStore;

/** Offline checks of the SOAP/XML adapter (WSDL import, XPath, faults, generated XML negatives). */
public class SoapTest {

    private static final String RESPONSE = "<?xml version=\"1.0\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + "<AddResponse xmlns=\"http://tempuri.org/\"><AddResult>42</AddResult></AddResponse></soap:Body></soap:Envelope>";
    private static final String FAULT = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>Input string was not in a correct format.</faultstring></s:Fault></s:Body></s:Envelope>";

    @Test
    public void xpathIgnoresPrefixesAndNamespaces() {
        Assert.assertEquals(XmlLite.xpath(RESPONSE, "/Envelope/Body/AddResponse/AddResult"), "42");
        Assert.assertEquals(XmlLite.xpath(RESPONSE, "//AddResult"), "42");
        Assert.assertNull(XmlLite.xpath(RESPONSE, "//Nope"));
        Assert.assertNull(XmlLite.xpath("{\"json\":true}", "//a"), "not XML");
        Assert.assertNull(XmlLite.xpath("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]><x>&e;</x>", "//x"), "DTDs are refused");
    }

    @Test
    public void faultsAreRecognised() {
        Assert.assertNull(XmlLite.fault(RESPONSE));
        Assert.assertEquals(XmlLite.fault(FAULT), "Input string was not in a correct format.");
        Assert.assertNull(XmlLite.fault("{\"error\":\"Fault\"}"));
    }

    @Test
    public void wsdlImportAndGeneratedXmlNegatives() throws Exception {
        ApiModel m = WsdlImporter.importFile(Paths.get("sample_apis/specs/calculator_dneonline.wsdl"), "calculator.wsdl");
        Assert.assertEquals(m.endpoints.size(), 4);
        ApiModel.Endpoint add = m.endpoint("Add");
        Assert.assertEquals(add.method, "POST");
        Assert.assertEquals(add.headers.get("SOAPAction"), "\"http://tempuri.org/Add\"");
        Assert.assertTrue(add.requestBody.asText().contains("<Add xmlns=\"http://tempuri.org/\"><intA>1</intA><intB>1</intB></Add>"));
        Assert.assertEquals(m.environments.get(0).baseUrl, "http://www.dneonline.com");

        ProjectStore store = new ProjectStore(Paths.get("target", "soap-test", "projects"), Paths.get("target", "soap-test", "reports"));
        AiMemory mem = new AiMemory(store);
        TestGenerator.Result g = TestGenerator.generateAll(m, new TestGenerator.Options(), mem, mem.load("SOAP_X"), null);
        long xml = g.tests.stream().filter(t -> t.category.equals("MALFORMED_XML") || t.category.equals("XXE")).count();
        Assert.assertTrue(xml >= 2, "XML negatives generated for SOAP endpoints: " + xml);
    }
}
