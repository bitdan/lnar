package run;

import com.ibm.pip.framework.config.ConfigManager;
import com.ibm.pip.framework.config.ConfigMonitor;

public class RunDataStart {
	public static void main(String[] args) {
		String configPath = "./config/config.xml";
		ConfigManager.main(new String[]{configPath});
		//ConfigMonitor.main(new String[]{configPath});
	}
}
