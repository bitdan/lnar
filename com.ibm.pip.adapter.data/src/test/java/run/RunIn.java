package run;

import com.ibm.pip.framework.config.ConfigManager;

public class RunIn {
	public static void main(String[] args) {
		String configPath = "./config/config.xml";
		ConfigManager.main(new String[]{configPath});
	}
}
