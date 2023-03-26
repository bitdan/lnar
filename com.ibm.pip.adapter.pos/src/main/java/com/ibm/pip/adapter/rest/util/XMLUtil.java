package com.ibm.pip.adapter.rest.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;
import org.jdom.output.DOMOutputter;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jdom.xpath.XPath;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;


public class XMLUtil {

	private static Logger logger = Logger.getLogger(XMLUtil.class);
	public static final String ENCODING_DEFAULT = "UTF-8";

	public XMLUtil() {
	}

	/**
	 * xml to string
	 * 
	 * @param doc
	 * @return
	 */
	public static String xml2string(Document doc) {
		Format format = Format.getPrettyFormat();
		XMLOutputter xmlout = new XMLOutputter(format);
		return xmlout.outputString(doc);
	}
	
	public static org.w3c.dom.Document convertJdomToW3C(Document doc)throws TransformerException{
		 // create JDOM to DOM converter:
        DOMOutputter output = new DOMOutputter();
        org.w3c.dom.Document dom = null;
        
        try {
        	dom = output.output(doc);
        }catch(JDOMException e) {
        	e.printStackTrace();
        }
		return dom;
	}
	
	public static byte[] convertW3CDocumentToBytes(org.w3c.dom.Document doc)throws TransformerException{
		Source source = new DOMSource( doc );
	    ByteArrayOutputStream out = new ByteArrayOutputStream();
	    Result result = new StreamResult(out);
	    TransformerFactory factory = TransformerFactory.newInstance();
	    factory.setAttribute("indent-number", new Integer(2));
	    
	    Transformer transformer = factory.newTransformer();
	    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
	    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.CDATA_SECTION_ELEMENTS, "yes"); 
                
	    transformer.transform(source, result);
	    return out.toByteArray();	    
	}
	
	public static void convertW3CDocumentToFile(org.w3c.dom.Document doc, String filePath)throws TransformerException{
		Source source = new DOMSource( doc );	    
	    StreamResult result = new StreamResult(new File(filePath));
	    TransformerFactory factory = TransformerFactory.newInstance();
	    factory.setAttribute("indent-number", new Integer(2));
	    
	    Transformer transformer = factory.newTransformer();
	    transformer.setOutputProperty(OutputKeys.INDENT, "yes");  
	    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.CDATA_SECTION_ELEMENTS, "yes"); 
        
	    transformer.transform(source, result);	      
	}
	
	public static String prettyFormatW3CDocument(org.w3c.dom.Document doc) throws Exception {
        
		org.w3c.dom.Element root = doc.getDocumentElement();
        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementationLS impl = (DOMImplementationLS) registry.getDOMImplementation("LS");
        LSSerializer writer = impl.createLSSerializer();
        writer.getDomConfig().setParameter("format-pretty-print", Boolean.TRUE);
        writer.getDomConfig().setParameter("xml-declaration", true);
        return writer.writeToString(root);
    }
	
	

	/**
	 * element to string
	 * 
	 * @param element
	 * @return
	 */
	public static String element2string(Element element) {
		Format format = Format.getPrettyFormat();
		XMLOutputter xmlout = new XMLOutputter(format);
		return xmlout.outputString(element);
	}

	/**
	 * get element by xpath ,then to string
	 * 
	 * @param element
	 * @return
	 */
	public static String xpath2string(Document doc, String xPath) {
		Format format = Format.getPrettyFormat();
		XMLOutputter xmlout = new XMLOutputter(format);
		return xmlout.outputString(getXMLNode(doc, xPath));
	}

	/**
	 * get single Element
	 * 
	 * @param in
	 *            XML bytes
	 * @param xPath
	 *            xpath
	 * @return element
	 */
	public static Element getXMLNode(byte[] in, String xPath) {
		Element element = null;
		Document doc = null;
		XPath jdomXPath = null;
		try {
			doc = getDocument(in, null);
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			element = (Element) jdomXPath.selectSingleNode(doc);
		} catch (Exception e) {

		} finally {
			doc = null;
			jdomXPath = null;
		}
		return element;
	}

	/**
	 * get single Element
	 * 
	 * @param in
	 *            XML bytes
	 * @param xPath
	 *            xpath
	 * @return  Element
	 */
	public static Element getXMLNode(Object doc, String xPath) {
		Element element = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("",
			// "urn:iso:std:iso:20022:tech:xsd:sct:pacs.008.001.02");
			element = (Element) jdomXPath.selectSingleNode(doc);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return element;
	}

	public static Element getXMLNode(Object doc, String xPath, Namespace namespace) {
		Element element = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace);
			element = (Element) jdomXPath.selectSingleNode(doc);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return element;
	}
	
	/**
	 * get single Element
	 * 
	 * @param in
	 *            XML bytes
	 * @param xPath
	 *            xpath
	 * @return  Element
	 */
	@SuppressWarnings("unchecked")
	public static List<Element> getXMLNodeList(Object doc, String xPath) {
		List<Element> nodeList = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			nodeList = (List<Element>) jdomXPath.selectNodes(doc);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return nodeList;
	}

	/**
	 * get some  Elements
	 * 
	 * @param in
	 *            XML bytes
	 * @param xPath
	 *            xpath
	 * @return  Element set
	 */
	@SuppressWarnings("unchecked")
	public static List<Element> getXMLNodeList(byte[] in, String xPath) {
		List<Element> nodeList = null;
		Document doc = null;
		XPath jdomXPath = null;
		try {
			doc = getDocument(in, null);
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			nodeList = (List<Element>) jdomXPath.selectNodes(doc);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return nodeList;
	}
	
	public static Document getDocument(String filePath) throws Exception {
		
		SAXBuilder builder = new SAXBuilder();
	    Document document = builder.build(new FileInputStream(new File(filePath)));
	    
	    return document;
	}

	/**
	 * get Document
	 * 
	 * @param in
	 * @return
	 */
	public static Document getDocument(byte[] in, String encoding) {
		SAXBuilder sax = null;
		Document doc = null;
		ByteArrayInputStream bais = null;
		InputSource ins = null;
		try {
			sax = new SAXBuilder();
			bais = new ByteArrayInputStream(in);
			ins = new InputSource(bais);
			if (encoding == null)
				encoding = ENCODING_DEFAULT;
			ins.setEncoding(encoding);
		/*	ins.setEncoding(null);*/
			doc = sax.build(ins);
		} catch (Exception e) {
			logger.debug("File error failed" + e);
		} finally {
			if (bais != null) {
				try {
					bais.close();
				} catch (Exception e) {
					logger.error(e);
				}
				bais = null;
			}
			sax = null;
		}
		return doc;
	}

	/**
	 * Set  Element' value
	 * 
	 * @param doc
	 * @param xPath
	 * @return
	 */
	public static void setXMLNameValue(Object doc, String xPath, String value, Namespace namespace) {		
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace);
			Element element = ((Element) jdomXPath.selectSingleNode(doc));
			
			if(element != null)
				element.setText(value);
		} catch (Exception e) {
			logger.debug("Set XMLNameValue failed");
		} finally {
			doc = null;
			jdomXPath = null;
		}
	}
	
	public static String getXMLNameValue(Object doc, String xPath) {
		String value = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("SCLSCT", "urn:BBkSCF");
			value = ((Element) jdomXPath.selectSingleNode(doc)).getValue();
		} catch (Exception e) {
			logger.debug("get XMLNameValue failed");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}
	
	/**
	 * 获取节点的值
	 * @param in
	 * @param xPath
	 * @return
	 */
	public static String getXMLNameValue(Object doc , String xPath,String spaces,String nameSpace ){
		String value = null ; 
		XPath jdomXPath = null;
		try{
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(spaces, nameSpace);
			value = ((Element)jdomXPath.selectSingleNode(doc)).getValue();
		}catch(Exception e){
//			Log.getInstance().stdError(e);
		}finally{
			doc = null;
			jdomXPath = null;
		}
		return value;
	}
		
	
	public static String getXMLNameValue(Object doc, String xPath,Namespace namespace ){
		String value = null ; 
		XPath jdomXPath = null;
		try{
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace);
			value = ((Element)jdomXPath.selectSingleNode(doc)).getValue();
		}catch(Exception e){
//			Log.getInstance().stdError(e);
		}finally{
			doc = null;
			jdomXPath = null;
		}
		return value;
	}

	/**
	 * 
	 * @param doc
	 * @param xPath
	 * @param namespace
	 * @param AttributeName
	 * @return
	 */
	public static String getXMLAttributeValue(Object doc, String xPath,
			String AttributeName) {
		String value = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			value = ((Element) jdomXPath.selectSingleNode(doc))
					.getAttributeValue(AttributeName);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}

	/**
	 * 
	 * @param doc
	 * @param xPath
	 * @param namespace
	 * @param AttributeName
	 * @return
	 */
	public static String getXMLAttributeValue(Document doc, String xPath,
			String namespace, String AttributeName) {
		String value = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace, doc.getRootElement()
					.getNamespaceURI());
			value = ((Element) jdomXPath.selectSingleNode(doc))
					.getAttributeValue(AttributeName);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}
	
	public static String getXMLAttributeValue(Document doc, String xPath,
			Namespace namespace, String AttributeName) {
		String value = null;
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace);
			value = ((Element) jdomXPath.selectSingleNode(doc))
					.getAttributeValue(AttributeName);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}
	
	public static void setXMLAttributeValue(Document doc, String xPath,
			Namespace namespace, String attributeName, String attributeValue) {		
		XPath jdomXPath = null;
		try {
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace);
			Element element = ((Element) jdomXPath.selectSingleNode(doc));
			if(element != null)
				element.setAttribute(attributeName, attributeValue);
			
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
	}

	/**
	 * 
	 * @param in
	 * @param xPath
	 * @param namespace
	 * @param AttributeName
	 * @return
	 */
	public static String getXMLAttributeValue(byte[] in, String xPath,
			String namespace, String AttributeName) {
		String value = null;
		Document doc = null;
		XPath jdomXPath = null;
		try {
			doc = getDocument(in, null);
			jdomXPath = XPath.newInstance(xPath);
			jdomXPath.addNamespace(namespace, doc.getRootElement()
					.getNamespaceURI());
			value = ((Element) jdomXPath.selectSingleNode(doc))
					.getAttributeValue(AttributeName);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}

	/**
	 * 
	 * @param in
	 * @param xPath
	 * @param namespace
	 * @param AttributeName
	 * @return
	 */
	public static String getXMLAttributeValue(byte[] in, String xPath,
			String AttributeName) {
		String value = null;
		Document doc = null;
		XPath jdomXPath = null;
		try {
			doc = getDocument(in, null);
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			value = ((Element) jdomXPath.selectSingleNode(doc))
					.getAttributeValue(AttributeName);
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}

	/**
	 * 
	 * @param in
	 * @param xPath
	 * @param encoding
	 * @return
	 */
	public static String getXMLNameValue(byte[] in, String xPath,
			String encoding) {
		String value = null;
		Document doc = null;
		XPath jdomXPath = null;
		try {
			doc = getDocument(in, encoding);
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			value = ((Element) jdomXPath.selectSingleNode(doc)).getValue();
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}

	/**
	 * get  Element'value by  XPath 
	 * 
	 * @param in
	 * @param xPath
	 * @return
	 */
	public static String getXMLNameValue(byte[] in, String xPath) {
		String value = null;
		Document doc = null;
		XPath jdomXPath = null;
		try {
			doc = getDocument(in, null);
			jdomXPath = XPath.newInstance(xPath);
			// jdomXPath.addNamespace("g3bulk", "urn:bcsis");
			value = ((Element) jdomXPath.selectSingleNode(doc)).getValue();
		} catch (Exception e) {
			logger.debug("");
		} finally {
			doc = null;
			jdomXPath = null;
		}
		return value;
	}

	/**
	 * delete element
	 * 
	 * @param in
	 * @param xPath
	 * @return
	 */
	public static void removeElement(Document document, String name) {
		Element root = document.getRootElement();
		root.removeChild(name);
	}	
	
	/**
	 *  delete element
	 * 
	 * @param in
	 * @param xPath
	 * @return
	 */
	public static void removeElement(Document document, Element element) {
		Element root = document.getRootElement();
		root.removeChild(element.getName());
	}

	public static void removeNamespace(Element element) {

		List<Namespace> additional = element.getAdditionalNamespaces();
		if (additional.size() == 0) {
			System.out.println(element.getNamespace());
			System.out.println(element.getNamespacePrefix());
			System.out.println(element.getNamespaceURI());
			Namespace ns = Namespace.getNamespace(element.getNamespacePrefix(),
					element.getNamespaceURI());

			element.removeNamespaceDeclaration(ns);
			System.out.println(element2string(element));

		}
		if (additional.size() > 0) {
			for (int i = additional.size(); --i >= 0;) {
				System.out.println(additional.get(i));
				element.removeNamespaceDeclaration(additional.get(i));
			}
		}
		
	}
	public static String MULTIAYER_SEP = "/";
	public static String[] getMultiayerArr(String name){
		return name.split(MULTIAYER_SEP);
	}
	public static Element getXMLElementWithOutNameSpace(Document fileSAADoc, String key) {
		// TODO Auto-generated method stub
		String[] nameArr = XMLUtil.getMultiayerArr(key);
		if(nameArr.length < 2)
		{
			return null;
		}
		Element ele = fileSAADoc.getRootElement();
		for(int i=1; i<nameArr.length; i++)
		{
			ele = ele.getChild(nameArr[i],null);
		}
		return ele;
	}
	public static String getXMLStringWithOutNameSpace(Document fileSAADoc, String key) {
		// TODO Auto-generated method stub
		String[] nameArr = XMLUtil.getMultiayerArr(key);
		if(nameArr.length < 2)
		{
			return null;
		}
		Element ele = fileSAADoc.getRootElement();
		for(int i=1; i<nameArr.length; i++)
		{
			ele = ele.getChild(nameArr[i],null);
			if(ele == null)
			{
				return null;
			}
		}
		return ele.getText();
	}
	
	//将XML转换成String输出
    public static byte[] xml2Bytes(Document document) throws Exception {

        Format format = Format.getCompactFormat();
        format.setEncoding("UTF-8");
        XMLOutputter xmlout = new XMLOutputter();
        
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        xmlout.output(document, bo);
        bo.toByteArray();
        return bo.toByteArray();
        
    }
	
	
}
