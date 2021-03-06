package com.dianping.cat.config.server;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.dal.jdbc.DalException;
import org.unidal.dal.jdbc.DalNotFoundException;
import org.unidal.helper.Threads;
import org.unidal.helper.Threads.Task;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.util.StringUtils;
import org.xml.sax.SAXException;

import com.dianping.cat.Cat;
import com.dianping.cat.config.content.ContentFetcher;
import com.dianping.cat.configuration.server.filter.entity.CrashLogDomain;
import com.dianping.cat.configuration.server.filter.entity.ServerFilterConfig;
import com.dianping.cat.configuration.server.filter.transform.DefaultSaxParser;
import com.dianping.cat.core.config.Config;
import com.dianping.cat.core.config.ConfigDao;
import com.dianping.cat.core.config.ConfigEntity;
import com.dianping.cat.helper.TimeHelper;

public class ServerFilterConfigManager implements Initializable {

	@Inject
	protected ConfigDao m_configDao;

	@Inject
	protected ContentFetcher m_fetcher;

	private ServerFilterConfig m_config;

	private static final String CONFIG_NAME = "serverFilter";

	private int m_configId;

	private long m_modifyTime;

	public boolean discardTransaction(String type, String name) {
		if ("Cache.web".equals(type) || "ABTest".equals(type)) {
			return true;
		}
		if (m_config.getTransactionTypes().contains(type) && m_config.getTransactionNames().contains(name)) {
			return true;
		}
		return false;
	}

	public ServerFilterConfig getConfig() {
		return m_config;
	}

	public List<CrashLogDomain> getCrashLogDomains() {
		return m_config.getCrashLogDomains();
	}

	public Set<String> getCrashLogDomainIds() {
		HashSet<String> domains = new HashSet<String>();

		for (CrashLogDomain domain : m_config.getCrashLogDomains()) {
			domains.add(domain.getId());
		}
		return domains;
	}

	public Set<String> getUnusedDomains() {
		Set<String> unusedDomains = new HashSet<String>();

		unusedDomains.addAll(getCrashLogDomainIds());
		unusedDomains.addAll(m_config.getDomains());
		return unusedDomains;
	}

	@Override
	public void initialize() throws InitializationException {
		try {
			Config config = m_configDao.findByName(CONFIG_NAME, ConfigEntity.READSET_FULL);
			String content = config.getContent();

			m_configId = config.getId();
			m_modifyTime = config.getModifyDate().getTime();
			m_config = DefaultSaxParser.parse(content);
		} catch (DalNotFoundException e) {
			try {
				String content = m_fetcher.getConfigContent(CONFIG_NAME);
				Config config = m_configDao.createLocal();

				config.setName(CONFIG_NAME);
				config.setContent(content);
				m_configDao.insert(config);
				m_configId = config.getId();
				m_config = DefaultSaxParser.parse(content);
			} catch (Exception ex) {
				Cat.logError(ex);
			}
		} catch (Exception e) {
			Cat.logError(e);
		}
		if (m_config == null) {
			m_config = new ServerFilterConfig();
		}
		Threads.forGroup("cat").start(new ConfigReloadTask());
	}

	public boolean insert(String xml) {
		try {
			m_config = DefaultSaxParser.parse(xml);

			return storeConfig();
		} catch (Exception e) {
			Cat.logError(e);
			return false;
		}
	}

	public boolean isCrashLog(String domain) {
		return m_config.getCrashLogDomains().contains(domain);
	}

	public boolean storeConfig() {
		try {
			Config config = m_configDao.createLocal();

			config.setId(m_configId);
			config.setKeyId(m_configId);
			config.setName(CONFIG_NAME);
			config.setContent(m_config.toString());
			m_configDao.updateByPK(config, ConfigEntity.UPDATESET_FULL);
		} catch (Exception e) {
			Cat.logError(e);
			return false;
		}
		return true;
	}

	public boolean validateDomain(String domain) {
		return !m_config.getDomains().contains(domain) && !m_config.getCrashLogDomains().contains(domain)
		      && StringUtils.isNotEmpty(domain);
	}

	public void refreshConfig() throws DalException, SAXException, IOException {
		Config config = m_configDao.findByName(CONFIG_NAME, ConfigEntity.READSET_FULL);
		long modifyTime = config.getModifyDate().getTime();

		synchronized (this) {
			if (modifyTime > m_modifyTime) {
				String content = config.getContent();
				ServerFilterConfig serverConfig = DefaultSaxParser.parse(content);

				m_config = serverConfig;
				m_modifyTime = modifyTime;
			}
		}
	}

	public class ConfigReloadTask implements Task {

		@Override
		public String getName() {
			return "Server-Filter-Config-Reload";
		}

		@Override
		public void run() {
			boolean active = true;

			while (active) {
				try {
					refreshConfig();
				} catch (Exception e) {
					Cat.logError(e);
				}
				try {
					Thread.sleep(TimeHelper.ONE_MINUTE);
				} catch (InterruptedException e) {
					active = false;
				}
			}
		}

		@Override
		public void shutdown() {
		}
	}

}
