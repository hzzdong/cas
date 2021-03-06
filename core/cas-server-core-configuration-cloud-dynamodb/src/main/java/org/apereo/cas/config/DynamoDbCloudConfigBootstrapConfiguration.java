package org.apereo.cas.config;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.google.common.base.Throwables;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;

import java.net.InetAddress;
import java.util.Map;
import java.util.Properties;

/**
 * This is {@link DynamoDbCloudConfigBootstrapConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@Configuration("dynamoDbCloudConfigBootstrapConfiguration")
public class DynamoDbCloudConfigBootstrapConfiguration implements PropertySourceLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamoDbCloudConfigBootstrapConfiguration.class);
    private static final String TABLE_NAME = "DynamoDbCasProperties";
    private static final long PROVISIONED_THROUGHPUT = 10;

    private enum ColumnNames {
        ID("id"),
        NAME("name"),
        VALUE("value");

        private final String name;

        ColumnNames(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Override
    public PropertySource<?> locate(final Environment environment) {
        final AmazonDynamoDBClient amazonDynamoDBClient = getAmazonDynamoDbClient(environment);
        createSettingsTable(amazonDynamoDBClient, false);

        final ScanRequest scan = new ScanRequest(TABLE_NAME);
        LOGGER.debug("Scanning table with request [{}]", scan);
        final ScanResult result = amazonDynamoDBClient.scan(scan);
        LOGGER.debug("Scanned table with result [{}]", scan);

        final Properties props = new Properties();
        result.getItems()
                .stream()
                .map(DynamoDbCloudConfigBootstrapConfiguration::retrieveSetting)
                .forEach(p -> props.put(p.getKey(), p.getValue()));
        return new PropertiesPropertySource(getClass().getSimpleName(), props);
    }

    private static Pair<String, Object> retrieveSetting(final Map<String, AttributeValue> entry) {
        final String name = entry.get(ColumnNames.NAME).getS();
        final String value = entry.get(ColumnNames.VALUE).getS();
        return Pair.of(name, value);
    }

    private static String getSetting(final Environment environment, final String key) {
        return environment.getProperty("cas.spring.cloud.dynamodb." + key);
    }

    private static AmazonDynamoDBClient getAmazonDynamoDbClient(final Environment environment) {
        final ClientConfiguration cfg = new ClientConfiguration();

        try {
            final String localAddress = getSetting(environment, "localAddress");
            if (StringUtils.isNotBlank(localAddress)) {
                cfg.setLocalAddress(InetAddress.getByName(localAddress));
            }
        } catch (final Exception e) {
            LOGGER.error(e.getMessage(), e);
        }

        final String key = getSetting(environment, "credentialAccessKey");
        final String secret = getSetting(environment, "credentialSecretKey");
        final AWSCredentials credentials = new BasicAWSCredentials(key, secret);

        final AmazonDynamoDBClient client;
        if (credentials == null) {
            client = new AmazonDynamoDBClient(cfg);
        } else {
            client = new AmazonDynamoDBClient(credentials, cfg);
        }

        final String endpoint = getSetting(environment, "endpoint");
        if (StringUtils.isNotBlank(endpoint)) {
            client.setEndpoint(endpoint);
        }

        final String region = getSetting(environment, "region");
        if (StringUtils.isNotBlank(region)) {
            client.setRegion(Region.getRegion(Regions.valueOf(region)));
        }

        final String regionOverride = getSetting(environment, "regionOverride");
        if (StringUtils.isNotBlank(regionOverride)) {
            client.setSignerRegionOverride(regionOverride);
        }
        return client;
    }

    private static void createSettingsTable(final AmazonDynamoDBClient amazonDynamoDBClient, final boolean deleteTables) {
        try {
            final CreateTableRequest request = new CreateTableRequest()
                    .withAttributeDefinitions(new AttributeDefinition(ColumnNames.ID.getName(), ScalarAttributeType.S))
                    .withKeySchema(new KeySchemaElement(ColumnNames.ID.getName(), KeyType.HASH))
                    .withProvisionedThroughput(new ProvisionedThroughput(PROVISIONED_THROUGHPUT, PROVISIONED_THROUGHPUT))
                    .withTableName(TABLE_NAME);

            if (deleteTables) {
                final DeleteTableRequest delete = new DeleteTableRequest(request.getTableName());
                LOGGER.debug("Sending delete request [{}] to remove table if necessary", delete);
                TableUtils.deleteTableIfExists(amazonDynamoDBClient, delete);
            }
            LOGGER.debug("Sending delete request [{}] to create table", request);
            TableUtils.createTableIfNotExists(amazonDynamoDBClient, request);

            LOGGER.debug("Waiting until table [{}] becomes active...", request.getTableName());
            TableUtils.waitUntilActive(amazonDynamoDBClient, request.getTableName());

            final DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(request.getTableName());
            LOGGER.debug("Sending request [{}] to obtain table description...", describeTableRequest);

            final TableDescription tableDescription = amazonDynamoDBClient.describeTable(describeTableRequest).getTable();
            LOGGER.debug("Located newly created table with description: [{}]", tableDescription);
        } catch (final Exception e) {
            throw Throwables.propagate(e);
        }
    }
}
