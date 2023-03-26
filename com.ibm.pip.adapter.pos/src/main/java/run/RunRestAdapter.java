package run;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.apache.commons.lang.time.DateUtils;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

import com.ibm.pip.framework.config.ConfigManager;

public class RunRestAdapter {
	public static void main(String[] args) {
		
		String path = "/Users/nansion/Downloads/test2.sql";
        File file = new File(path);
        StringBuilder result = new StringBuilder();
        try{
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));//构造一个BufferedReader类来读取文件

            boolean istmp = false;
            String s = null;
            String id = null;
            while((s = br.readLine())!=null){//使用readLine方法，一次读一行
               // result.append( System.lineSeparator() + s);
                
            	if(s.contains("UPDATE `order_admin`.`adm_basic_t`"))
            	{
            		result.append("update adm_basic_t set " );
            	}
            	
            	if(s.contains("### WHERE"))
            	{
            		istmp = true;
            	}
            	
            	if(istmp && s.contains("###   @1="))
            	{
            	    id = s.substring(s.indexOf("@1=")+3);
            	}
            	
            	if(istmp && s.contains("###   @3="))
            	{
            	    String tmp = s.substring(s.indexOf("@3=")+3);
            	    if(tmp.equals("NULL"))
            	    	tmp = null;
            		result.append("room_code = " + tmp);
            	}
            	
            	if(istmp && s.contains("###   @4="))
            	{
            		String tmp = s.substring(s.indexOf("@4=")+3);
            		if(tmp.equals("NULL"))
            	    	tmp = null;
            		result.append(", room_name = " + tmp);
            	}
            	
            	if(s.contains("### SET"))
            	{
            		result.append(" where work_order_code = " + id + ";");
            		id = null;
            		result.append( System.lineSeparator());
            		istmp = false;
            	}
            }
        	System.out.println(result.toString());
            br.close();
        }catch(Exception e){
            e.printStackTrace();
        }


//		
//		String configPath = "./config/config.xml";
//		ConfigManager.main(new String[]{configPath});
		
	}
	

}
