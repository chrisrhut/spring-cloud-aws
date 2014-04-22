/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elasticspring.jdbc.config.xml;

import org.elasticspring.config.AmazonWebserviceClientConfigurationUtils;
import org.elasticspring.context.config.xml.GlobalBeanDefinitionUtils;
import org.elasticspring.jdbc.datasource.TomcatJdbcDataSourceFactory;
import org.elasticspring.jdbc.rds.AmazonRdsDataSourceFactoryBean;
import org.elasticspring.jdbc.rds.AmazonRdsReadReplicaAwareDataSourceFactoryBean;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * {@link org.springframework.beans.factory.xml.BeanDefinitionParser} parser implementation for the datasource
 * element. Parses the element and constructs a fully configured {@link AmazonRdsDataSourceFactoryBean} bean
 * definition. Also creates a bean definition for the {@link com.amazonaws.services.rds.AmazonRDSClient} if there is
 * not
 * already an
 * existing one this application context.
 *
 * @author Agim Emruli
 * @since 1.0
 */
class AmazonRdsBeanDefinitionParser extends AbstractBeanDefinitionParser {

	static final String DB_INSTANCE_IDENTIFIER = "db-instance-identifier";
	private static final String AMAZON_RDS_CLIENT_CLASS_NAME = "com.amazonaws.services.rds.AmazonRDSClient";
	private static final String IDENTITY_MANAGEMENT_CLASS_NAME = "com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient";
	private static final String USER_TAG_FACTORY_BEAN_CLASS_NAME = "org.elasticspring.jdbc.rds.AmazonRdsDataSourceUserTagsFactoryBean";
	private static final String USERNAME = "username";
	private static final String PASSWORD = "password";

	/**
	 * Creates a {@link org.elasticspring.jdbc.datasource.DataSourceFactory} implementation. Uses the
	 * TomcatJdbcDataSourceFactory implementation and passes all pool attributes from the xml directly to the class
	 * (through setting the bean properties).
	 *
	 * @param element
	 * 		- The datasource element which may contain a pool-attributes element
	 * @return - fully configured bean definition for the DataSourceFactory
	 */
	private static AbstractBeanDefinition createDataSourceFactoryBeanDefinition(Element element) {
		BeanDefinitionBuilder datasourceFactoryBuilder = BeanDefinitionBuilder.rootBeanDefinition(TomcatJdbcDataSourceFactory.class);
		Element poolAttributes = DomUtils.getChildElementByTagName(element, "pool-attributes");
		if (poolAttributes != null) {
			NamedNodeMap attributes = poolAttributes.getAttributes();
			for (int i = 0, x = attributes.getLength(); i < x; i++) {
				Node item = attributes.item(i);
				datasourceFactoryBuilder.addPropertyValue(item.getNodeName(), item.getNodeValue());
			}
		}

		return datasourceFactoryBuilder.getBeanDefinition();
	}

	private static void registerUserTagsMapIfNecessary(Element element, ParserContext parserContext, BeanDefinitionHolder rdsInstanceHolder) {
		if (!StringUtils.hasText(element.getAttribute("user-tags-map"))) {
			return;
		}

		BeanDefinitionHolder identityManagement = AmazonWebserviceClientConfigurationUtils.registerAmazonWebserviceClient(parserContext.getRegistry(),
				IDENTITY_MANAGEMENT_CLASS_NAME, element.getAttribute("region-provider"), element.getAttribute("region"));

		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(USER_TAG_FACTORY_BEAN_CLASS_NAME);
		builder.addConstructorArgReference(rdsInstanceHolder.getBeanName());
		builder.addConstructorArgValue(element.getAttribute(DB_INSTANCE_IDENTIFIER));
		builder.addConstructorArgReference(identityManagement.getBeanName());

		// Use custom region-provider of data source
		if (StringUtils.hasText(element.getAttribute("region-provider"))) {
			BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(MethodInvokingFactoryBean.class);
			beanDefinitionBuilder.addPropertyValue("targetObject", new RuntimeBeanReference(element.getAttribute("region-provider")));
			beanDefinitionBuilder.addPropertyValue("targetMethod", "getRegion");
			builder.addPropertyValue("region", beanDefinitionBuilder.getBeanDefinition());
		}

		if (StringUtils.hasText(element.getAttribute("region"))) {
			BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition("com.amazonaws.regions.Region");
			beanDefinitionBuilder.setFactoryMethod("getRegion");
			beanDefinitionBuilder.addConstructorArgValue(element.getAttribute("region"));
			builder.addPropertyValue("region", beanDefinitionBuilder.getBeanDefinition());
		}

		String resourceResolverBeanName = GlobalBeanDefinitionUtils.retrieveResourceIdResolverBeanName(parserContext.getRegistry());
		builder.addPropertyReference("resourceIdResolver", resourceResolverBeanName);

		parserContext.getRegistry().registerBeanDefinition(element.getAttribute("user-tags-map"), builder.getBeanDefinition());
	}

	@Override
	protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
		BeanDefinitionBuilder datasourceBuilder;
		if (Boolean.TRUE.toString().equalsIgnoreCase(element.getAttribute("read-replica-support"))) {
			datasourceBuilder = BeanDefinitionBuilder.rootBeanDefinition(AmazonRdsReadReplicaAwareDataSourceFactoryBean.class);
		} else {
			datasourceBuilder = BeanDefinitionBuilder.rootBeanDefinition(AmazonRdsDataSourceFactoryBean.class);
		}

		if (StringUtils.hasText(element.getAttribute("region-provider")) && StringUtils.hasText(element.getAttribute("region"))) {
			parserContext.getReaderContext().error("region and region-provider attribute must not be used together", element);
		}

		BeanDefinitionHolder holder = AmazonWebserviceClientConfigurationUtils.registerAmazonWebserviceClient(parserContext.getRegistry(),
				AMAZON_RDS_CLIENT_CLASS_NAME, element.getAttribute("region-provider"), element.getAttribute("region"));


		//Constructor (mandatory) args
		datasourceBuilder.addConstructorArgReference(holder.getBeanName());
		datasourceBuilder.addConstructorArgValue(element.getAttribute(DB_INSTANCE_IDENTIFIER));
		datasourceBuilder.addConstructorArgValue(element.getAttribute(PASSWORD));

		//optional args
		if (StringUtils.hasText(element.getAttribute(USERNAME))) {
			datasourceBuilder.addPropertyValue(USERNAME, element.getAttribute(USERNAME));
		}

		datasourceBuilder.addPropertyValue("dataSourceFactory", createDataSourceFactoryBeanDefinition(element));

		//Register registry to enable cloud formation support
		String resourceResolverBeanName = GlobalBeanDefinitionUtils.retrieveResourceIdResolverBeanName(parserContext.getRegistry());
		datasourceBuilder.addPropertyReference("resourceIdResolver", resourceResolverBeanName);

		registerUserTagsMapIfNecessary(element, parserContext, holder);

		return datasourceBuilder.getBeanDefinition();
	}
}