package com.ibm.pip.adapter.sunda.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import java.io.*;
import java.util.*;

public class XmlUtil {
    /**
     * XML节点转换JSON对象
     *
     * @param element 节点
     * @param object  新的JSON存储
     * @return JSON对象
     */
    private static JSONObject xmlToJson(Element element, JSONObject object) {
        List<Element> elements = element.elements();
        for (Element child : elements) {
            Object value = object.get(child.getName());
            Object newValue;

            if (child.elements().size() > 0) {
                JSONObject jsonObject = xmlToJson(child, new JSONObject(true));
                if (!jsonObject.isEmpty()) {
                    newValue = jsonObject;
                } else {
                    newValue = child.getText();
                }
            } else {
                newValue = child.getText();
            }

            List<Attribute> attributes = child.attributes();
            if (!attributes.isEmpty()) {
                JSONObject attrJsonObject = new JSONObject();
                for (Attribute attribute : attributes) {
                    attrJsonObject.put(attribute.getName(), attribute.getText());
                    attrJsonObject.put("content", newValue);
                }
                newValue = attrJsonObject;
            }

            if (newValue != null) {
                if (value != null) {
                    if (value instanceof JSONArray) {
                        ((JSONArray) value).add(newValue);
                    } else {
                        JSONArray array = new JSONArray();
                        array.add(value);
                        array.add(newValue);
                        object.put(child.getName(), array);
                    }
                } else {
                    object.put(child.getName(), newValue);
                }
            }
        }
        return object;
    }

    /**
     * XML字符串转换JSON对象
     *
     * @param xmlStr XML字符串
     * @return JSON对象
     */
    public static JSONObject xmlToJson(String xmlStr) {
        JSONObject result = new JSONObject(true);
        SAXReader xmlReader = new SAXReader();
        try {
            Document document = xmlReader.read(new StringReader(xmlStr));
            Element element = document.getRootElement();
            return xmlToJson(element, result);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * XML文件转换JSON对象
     *
     * @param file 文件路径
     * @param node 选择节点
     * @return JSON对象
     */
    public static JSONObject xmlToJson(File file, String node) {
        JSONObject result = new JSONObject(true);
        SAXReader xmlReader = new SAXReader();
        try {
            Document document = xmlReader.read(file);
            Element element;
            if (StringUtils.isBlank(node)) {
                element = document.getRootElement();
            } else {
                element = (Element) document.selectSingleNode(node);
            }
            return xmlToJson(element, result);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
}
