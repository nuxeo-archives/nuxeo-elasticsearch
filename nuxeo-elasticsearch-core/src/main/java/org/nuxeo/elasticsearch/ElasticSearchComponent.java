/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Tiry
 *     bdelbosc
 */

package org.nuxeo.elasticsearch;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.UNSUPPORTED_ACL;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.ACL_FIELD;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.CHILDREN_FIELD;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.DOC_TYPE;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.elasticsearch.action.admin.cluster.tasks.PendingClusterTasksResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.status.IndicesStatusRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.IndexDeleteByQueryResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.service.PendingClusterTask;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.ImmutableSettings.Builder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.AndFilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.nuxeo.ecm.automation.jaxrs.io.documents.JsonESDocumentWriter;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.SortInfo;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventProducer;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.api.ElasticSearchIndexing;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.commands.IndexingCommand;
import org.nuxeo.elasticsearch.config.ElasticSearchIndexConfig;
import org.nuxeo.elasticsearch.config.ElasticSearchLocalConfig;
import org.nuxeo.elasticsearch.config.ElasticSearchRemoteConfig;
import org.nuxeo.elasticsearch.nxql.NxqlQueryConverter;
import org.nuxeo.elasticsearch.work.IndexingWorker;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.metrics.MetricsService;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;

/**
 * Component used to configure and manage ElasticSearch integration
 *
 * @author <a href="mailto:tdelprat@nuxeo.com">Tiry</a>
 *
 */
public class ElasticSearchComponent extends DefaultComponent implements
        ElasticSearchService, ElasticSearchIndexing, ElasticSearchAdmin {

    private static final Log log = LogFactory
            .getLog(ElasticSearchComponent.class);

    public static final String EP_REMOTE = "elasticSearchRemote";

    public static final String EP_LOCAL = "elasticSearchLocal";

    public static final String EP_INDEX = "elasticSearchIndex";

    public static final String ID_FIELD = "_id";

    protected Node localNode;

    protected Client client;

    protected boolean indexInitDone = false;

    // indexing command that where received before the index initialization
    protected List<IndexingCommand> stackedCommands = new ArrayList<>();

    protected final Map<String, String> indexNames = new HashMap<String, String>();

    // temporary hack until we are able to list pending indexing jobs cluster
    // wide
    protected final Set<String> pendingWork = Collections
            .synchronizedSet(new HashSet<String>());

    protected final Set<String> pendingCommands = Collections
            .synchronizedSet(new HashSet<String>());

    protected Map<String, ElasticSearchIndexConfig> indexes = new HashMap<String, ElasticSearchIndexConfig>();

    // Metrics
    protected final MetricRegistry registry = SharedMetricRegistries
            .getOrCreate(MetricsService.class.getName());

    protected Timer searchTimer;

    protected Timer fetchTimer;

    protected Timer deleteTimer;

    protected Timer indexTimer;

    protected Timer bulkIndexTimer;

    private ElasticSearchLocalConfig localConfig;

    private ElasticSearchRemoteConfig remoteConfig;

    private AtomicInteger totalCommandProcessed = new AtomicInteger(0);

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        if (EP_LOCAL.equals(extensionPoint)) {
            release();
            localConfig = (ElasticSearchLocalConfig) contribution;
            remoteConfig = null;
        } else if (EP_REMOTE.equals(extensionPoint)) {
            release();
            remoteConfig = (ElasticSearchRemoteConfig) contribution;
            localConfig = null;
        } else if (EP_INDEX.equals(extensionPoint)) {
            ElasticSearchIndexConfig idx = (ElasticSearchIndexConfig) contribution;
            ElasticSearchIndexConfig previous = indexes.put(idx.getType(), idx);
            idx.merge(previous);
            if (DOC_TYPE.equals(idx.getType())) {
                log.info("Associate index " + idx.getName()
                        + " with repository: " + idx.getRepositoryName());
                indexNames.put(idx.getRepositoryName(), idx.getName());
            }
        }
    }

    public ElasticSearchLocalConfig getLocalConfig() {
        if (Framework.isTestModeSet() && localConfig == null
                && remoteConfig == null) {
            // automatically generate a test config
            localConfig = new ElasticSearchLocalConfig();
            localConfig.setHttpEnabled(true);
            localConfig.setIndexStorageType("memory");
            localConfig.setNodeName("nuxeoTestNode");
            // use something random so we don't join an existing cluster
            localConfig.setClusterName("nuxeoTestCluster-"
                    + RandomStringUtils.randomAlphanumeric(6));
            remoteConfig = null;
        }
        return localConfig;
    }

    protected void schedulePostCommitIndexing(IndexingCommand cmd)
            throws ClientException {
        try {
            EventProducer evtProducer = Framework
                    .getLocalService(EventProducer.class);
            Event indexingEvent = cmd.asIndexingEvent();
            evtProducer.fireEvent(indexingEvent);
        } catch (Exception e) {
            throw ClientException.wrap(e);
        }
    }

    @Override
    public void indexNow(List<IndexingCommand> cmds) throws ClientException {
        if (!indexInitDone) {
            log.debug("Delaying indexing commands: Waiting for Index to be initialized.");
            stackedCommands.addAll(cmds);
            return;
        }
        processBulkDeleteCommands(cmds);
        Context stopWatch = bulkIndexTimer.time();
        try {
            processBulkIndexCommands(cmds);
        } finally {
            stopWatch.stop();
        }
        markCommandExecuted(cmds);
    }

    protected void processBulkDeleteCommands(List<IndexingCommand> cmds) {
        // Can be optimized with a single delete by query
        for (IndexingCommand cmd : cmds) {
            if ((cmd.getTargetDocument() != null)
                    && (IndexingCommand.DELETE.equals(cmd.getName()))) {
                Context stopWatch = deleteTimer.time();
                try {
                    processDeleteCommand(cmd);
                } finally {
                    stopWatch.stop();
                }
            }
        }
    }

    protected void processBulkIndexCommands(List<IndexingCommand> cmds)
            throws ClientException {
        BulkRequestBuilder bulkRequest = getClient().prepareBulk();
        for (IndexingCommand cmd : cmds) {
            if ((cmd.getTargetDocument() == null)
                    || (IndexingCommand.DELETE.equals(cmd.getName()))) {
                continue;
            }
            if (log.isTraceEnabled()) {
                log.trace("Sending bulk indexing request to Elasticsearch: "
                        + cmd.toString());
            }
            IndexRequestBuilder idxRequest = buildESIndexingRequest(cmd);
            bulkRequest.add(idxRequest);
        }
        if (bulkRequest.numberOfActions() > 0) {
            if (log.isDebugEnabled()) {
                log.debug(String
                        .format("Index bulk request: curl -XPOST 'http://localhost:9200/_bulk' -d '%s'",
                                bulkRequest.request().requests().toString()));
            }
            BulkResponse response = bulkRequest.execute().actionGet();
            if (response.hasFailures()) {
                log.error(response.buildFailureMessage());
            }
        }
    }

    @Override
    public void indexNow(IndexingCommand cmd) throws ClientException {
        if (cmd.getTargetDocument() == null) {
            return;
        }
        if (!indexInitDone) {
            stackedCommands.add(cmd);
            log.debug("Delaying indexing command: Waiting for Index to be initialized.");
            return;
        }
        if (log.isTraceEnabled()) {
            log.trace("Sending indexing request to Elasticsearch "
                    + cmd.toString());
        }
        if (IndexingCommand.DELETE.equals(cmd.getName())) {
            Context stopWatch = deleteTimer.time();
            try {
                processDeleteCommand(cmd);
            } finally {
                stopWatch.stop();
            }
        } else {
            Context stopWatch = indexTimer.time();
            try {
                processIndexCommand(cmd);
            } finally {
                stopWatch.stop();
            }
        }
        markCommandExecuted(cmd);
    }

    protected void processIndexCommand(IndexingCommand cmd)
            throws ClientException {
        DocumentModel doc = cmd.getTargetDocument();
        assert (doc != null);
        IndexRequestBuilder request = buildESIndexingRequest(cmd);
        if (log.isDebugEnabled()) {
            log.debug(String
                    .format("Index request: curl -XPUT 'http://localhost:9200/%s/%s/%s' -d '%s'",
                            getRepositoryIndex(doc.getRepositoryName()), DOC_TYPE, doc.getId(), request
                                    .request().toString()));
        }
        request.execute().actionGet();
    }

    protected void processDeleteCommand(IndexingCommand cmd) {
        DocumentModel doc = cmd.getTargetDocument();
        assert (doc != null);
        String indexName = getRepositoryIndex(doc.getRepositoryName());
        DeleteRequestBuilder request = getClient().prepareDelete(indexName,
                DOC_TYPE, doc.getId());
        if (log.isDebugEnabled()) {
            log.debug(String
                    .format("Delete request: curl -XDELETE 'http://localhost:9200/%s/%s/%s' -d '%s'",
                            indexName, DOC_TYPE, doc.getId(), request
                                    .request().toString()));
        }
        request.execute().actionGet();
        if (cmd.isRecurse()) {
            DeleteByQueryRequestBuilder deleteRequest = getClient()
                    .prepareDeleteByQuery(indexName).setQuery(
                            QueryBuilders.constantScoreQuery(FilterBuilders
                                    .termFilter(CHILDREN_FIELD,
                                            doc.getPathAsString())));
            if (log.isDebugEnabled()) {
                log.debug(String
                        .format("Delete byQuery request: curl -XDELETE 'http://localhost:9200/%s/%s/_query' -d '%s'",
                                indexName, DOC_TYPE, request.request()
                                        .toString()));
            }
            DeleteByQueryResponse responses = deleteRequest.execute()
                    .actionGet();
            for (IndexDeleteByQueryResponse response : responses) {
                if (response.getFailedShards() > 0) {
                    log.error(String.format(
                            "Delete byQuery fails on shard:%d out of %d",
                            response.getFailedShards(),
                            response.getTotalShards()));
                }
            }
        }
    }

    protected IndexRequestBuilder buildESIndexingRequest(IndexingCommand cmd)
            throws ClientException {
        DocumentModel doc = cmd.getTargetDocument();
        try {
            JsonFactory factory = new JsonFactory();
            XContentBuilder builder = jsonBuilder();
            JsonGenerator jsonGen = factory.createJsonGenerator(builder
                    .stream());
            JsonESDocumentWriter.writeESDocument(jsonGen, doc,
                    cmd.getSchemas(), null);
            return getClient().prepareIndex(
                    getRepositoryIndex(doc.getRepositoryName()),
                    DOC_TYPE, doc.getId()).setSource(builder);
        } catch (Exception e) {
            throw new ClientException(
                    "Unable to create index request for Document "
                            + doc.getId(), e);
        }
    }

    protected void markCommandExecuted(List<IndexingCommand> cmds) {
        for (IndexingCommand cmd : cmds) {
            markCommandExecuted(cmd);
        }
    }

    protected void markCommandExecuted(IndexingCommand cmd) {
        pendingWork.remove(getWorkKey(cmd.getTargetDocument()));
        boolean isRemoved = pendingCommands.remove(cmd.getId());
        if (isRemoved) {
            totalCommandProcessed.addAndGet(1);
        }
    }

    @Override
    public void scheduleIndexing(IndexingCommand cmd) throws ClientException {
        DocumentModel doc = cmd.getTargetDocument();
        if (doc == null) {
            return;
        }
        boolean added = pendingCommands.add(cmd.getId());
        if (!added) {
            if (log.isDebugEnabled()) {
                log.debug("Skip indexing for " + doc
                        + " since it is already scheduled");
            }
            return;
        }
        added = pendingWork.add(getWorkKey(doc));
        if (!added) {
            if (log.isDebugEnabled()) {
                log.debug("Skip indexing for " + doc
                        + " since it is already scheduled");
            }
            return;
        }

        if (cmd.isSync()) {
            if (log.isDebugEnabled()) {
                log.debug("Schedule PostCommit indexing request "
                        + cmd.toString());
            }
            schedulePostCommitIndexing(cmd);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Schedule Async indexing request  " + cmd.toString());
            }
            WorkManager wm = Framework.getLocalService(WorkManager.class);
            IndexingWorker idxWork = new IndexingWorker(cmd);
            wm.schedule(idxWork, true);
        }
    }

    /**
     * Get the elastic search index for a repository
     */
    protected String getRepositoryIndex(String repositoryName) {
        String ret = indexNames.get(repositoryName);
        if (ret == null) {
            throw new NoSuchElementException("No index defined for repository: "
                    + repositoryName);
        }
        return ret;
    }

    /**
     * Get the elastic search indexes for searches
     */
    protected String[] getSearchIndexes() {
        return indexNames.values().toArray(new String[0]);
    }

    private String getSearchIndexesAsString() {
        return StringUtils.join(indexNames.values(), ',');
    }

    @Override public void refreshRepositoryIndex(String repositoryName) {
        if (log.isDebugEnabled()) {
            log.debug("Refreshing index associated with repo: " + repositoryName);
        }
         getClient().admin().indices().prepareRefresh(
                getRepositoryIndex(repositoryName)).execute()
                .actionGet();
        if (log.isDebugEnabled()) {
            log.debug("Refreshing index done");
        }
    }

    @Override public void flushRepositoryIndex(String repositoryName) {
        log.info("Flushing index associated with repo: " + repositoryName);
        getClient().admin().indices().prepareFlush(getRepositoryIndex(
                repositoryName)).execute()
                .actionGet();
        if (log.isDebugEnabled()) {
            log.debug("Flushing index done");
        }
    }

    @Override
    public void refresh() {
        for (String RepositoryName: indexNames.keySet()) {
            refreshRepositoryIndex(RepositoryName);
        }
    }

    @Override
    public void flush() {
        for (String RepositoryName: indexNames.keySet()) {
            flushRepositoryIndex(RepositoryName);
        }
    }

    @Override
    public Client getClient() {
        if (client == null) {
            ElasticSearchLocalConfig lConf = getLocalConfig();
            if (lConf != null) {
                log.info("Creating a local ES node inJVM");
                Builder sBuilder = ImmutableSettings.settingsBuilder();
                sBuilder.put("http.enabled", lConf.httpEnabled())
                        .put("path.data", lConf.getDataPath())
                        .put("index.number_of_shards", 1)
                        .put("index.number_of_replicas", 1)
                        .put("cluster.name", lConf.getClusterName())
                        .put("node.name", lConf.getNodeName());
                if (lConf.getIndexStorageType() != null) {
                    sBuilder.put("index.store.type",
                            lConf.getIndexStorageType());
                    if (lConf.getIndexStorageType().equals("memory")) {
                        sBuilder.put("gateway.type", "none");
                    }
                }
                Settings settings = sBuilder.build();
                log.debug("Using settings: " + settings.toDelimitedString(','));
                localNode = NodeBuilder.nodeBuilder().local(true)
                        .settings(settings).node();
                client = localNode.start().client();
                client.admin().cluster().prepareHealth()
                        .setWaitForYellowStatus().execute().actionGet();
            } else if (remoteConfig != null) {
                log.info("Connecting to an ES cluster");
                Builder builder = ImmutableSettings
                        .settingsBuilder()
                        .put("cluster.name", remoteConfig.getClusterName())
                        .put("client.transport.nodes_sampler_interval",
                                remoteConfig.getSamplerInterval())
                        .put("index.number_of_shards",
                                remoteConfig.getNumberOfShards())
                        .put("index.number_of_replicas",
                                remoteConfig.getNumberOfReplicas())
                        .put("client.transport.ping_timeout",
                                remoteConfig.getPingTimeout())
                        .put("client.transport.ignore_cluster_name",
                                remoteConfig.isIgnoreClusterName())
                        .put("client.transport.sniff",
                                remoteConfig.isClusterSniff());
                Settings settings = builder.build();
                if (log.isDebugEnabled()) {
                    log.debug("Using settings: "
                            + settings.toDelimitedString(','));
                }
                TransportClient tClient = new TransportClient(settings);
                String[] addresses = remoteConfig.getAddresses();
                if (addresses == null) {
                    log.error("You need to provide an addressList to join a cluster");
                } else {
                    for (String item : remoteConfig.getAddresses()) {
                        String[] address = item.split(":");
                        log.info("Add transport address: " + item);
                        try {
                            InetAddress inet = InetAddress
                                    .getByName(address[0]);
                            tClient.addTransportAddress(new InetSocketTransportAddress(
                                    inet, Integer.parseInt(address[1])));
                        } catch (UnknownHostException e) {
                            log.error("Unable to resolve host " + address[0], e);
                        }
                    }
                }
                client = tClient;
            }
            if (client != null) {
                try {
                    client.admin().indices().status(new IndicesStatusRequest())
                            .get();
                } catch (InterruptedException | ExecutionException
                        | NoNodeAvailableException e) {
                    log.error(
                            "Failed to connect to elasticsearch: "
                                    + e.getMessage(), e);
                    client = null;
                }
            }
        }
        return client;
    }

    @Override
    public DocumentModelList query(CoreSession session, String nxql, int limit,
            int offset, SortInfo... sortInfos) throws ClientException {
        QueryBuilder queryBuilder = NxqlQueryConverter.toESQueryBuilder(nxql);

        // handle the built-in order by clause
        if (nxql.toLowerCase().contains("order by")) {
            List<SortInfo> builtInSortInfos = NxqlQueryConverter
                    .getSortInfo(nxql);
            if (sortInfos != null) {
                Collections.addAll(builtInSortInfos, sortInfos);
            }
            sortInfos = builtInSortInfos.toArray(new SortInfo[builtInSortInfos
                    .size()]);
        }
        return query(session, queryBuilder, limit, offset, sortInfos);
    }

    @Override
    public DocumentModelList query(CoreSession session,
            QueryBuilder queryBuilder, int limit, int offset,
            SortInfo... sortInfos) throws ClientException {
        long totalSize;
        List<String> ids;
        Context stopWatch = searchTimer.time();
        try {
            // Initialize request
            SearchRequestBuilder request = getClient()
                    .prepareSearch(getSearchIndexes()).setTypes(DOC_TYPE)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .addField(ID_FIELD).setFrom(offset).setSize(limit);
            // Add security filter
            AndFilterBuilder aclFilter = null;
            Principal principal = session.getPrincipal();
            if (principal != null) {
                if (!(principal instanceof NuxeoPrincipal && ((NuxeoPrincipal) principal)
                        .isAdministrator())) {
                    String[] principals = SecurityService
                            .getPrincipalsToCheck(principal);
                    // we want an ACL that match principals but we discard
                    // unsupported ACE that contains negative ACE
                    aclFilter = FilterBuilders.andFilter(FilterBuilders
                            .inFilter(ACL_FIELD, principals), FilterBuilders
                            .notFilter(FilterBuilders.inFilter(ACL_FIELD,
                                    UNSUPPORTED_ACL)));
                }
            }
            if (aclFilter == null) {
                request.setQuery(queryBuilder);
            } else {
                request.setQuery(QueryBuilders.filteredQuery(queryBuilder,
                        aclFilter));
            }
            // Add sort
            if (sortInfos != null) {
                for (SortInfo sortInfo : sortInfos) {
                    request.addSort(sortInfo.getSortColumn(), sortInfo
                            .getSortAscending() ? SortOrder.ASC
                            : SortOrder.DESC);
                }
            }
            // Execute the ES query
            if (log.isDebugEnabled()) {
                log.debug(String
                        .format("Search query: curl -XGET 'http://localhost:9200/%s/%s/_search?pretty' -d '%s'",
                                getSearchIndexesAsString(), DOC_TYPE,
                                request.toString()));
            }
            SearchResponse response = request.execute().actionGet();
            if (log.isDebugEnabled()) {
                log.debug("Response: " + response.toString());
            }
            // Get the list of ids
            ids = new ArrayList<String>(limit);
            for (SearchHit hit : response.getHits()) {
                ids.add(hit.getId());
            }
            totalSize = response.getHits().getTotalHits();
        } finally {
            stopWatch.stop();
        }
        DocumentModelList ret = new DocumentModelListImpl(ids.size());
        stopWatch = fetchTimer.time();
        try {
            ((DocumentModelListImpl) ret).setTotalSize(totalSize);
            // Fetch the document model
            if (!ids.isEmpty()) {
                try {
                    ret.addAll(fetchDocuments(ids, session));
                } catch (ClientException e) {
                    log.error(e.getMessage(), e);
                }
            }
        } finally {
            stopWatch.stop();
        }
        return ret;
    }

    @Override
    public void applicationStarted(ComponentContext context) throws Exception {

        super.applicationStarted(context);
        if (remoteConfig == null && getLocalConfig() == null) {
            log.warn("Unable to initialize Elasticsearch service : no configuration is provided");
            return;
        }
        // init metrics
        searchTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "search"));
        fetchTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "fetch"));
        indexTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "index"));
        deleteTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "delete"));
        bulkIndexTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "bulkIndex"));
        // init client
        getClient();
        // init indexes if needed
        initIndexes(false);
    }

    @Override
    public void initIndexes(boolean dropIfExists) {
        for (ElasticSearchIndexConfig idx : indexes.values()) {
            initIndex(idx, dropIfExists);
        }
        indexInitDone = true;
        if (!stackedCommands.isEmpty()) {
            log.info("Processing indexing command stacked during startup");
            boolean txCreated = false;
            if (!TransactionHelper.isTransactionActive()) {
                txCreated = TransactionHelper.startTransaction();
            }
            try {
                for (final IndexingCommand cmd : stackedCommands) {
                    new UnrestrictedSessionRunner(cmd.getRepository()) {
                        @Override
                        public void run() throws ClientException {
                            cmd.refresh(session);
                            indexNow(cmd);
                        }
                    }.runUnrestricted();
                }
            } catch (Exception e) {
                log.error(
                        "Unable to flush pending indexing commands: "
                                + e.getMessage(), e);
            } finally {
                if (txCreated) {
                    TransactionHelper.commitOrRollbackTransaction();
                }
                stackedCommands.clear();
                log.debug("Done");
            }
        }
    }

    protected void initIndex(ElasticSearchIndexConfig conf, boolean dropIfExists) {
        if (!conf.mustCreate()) {
            return;
        }
        log.info(String.format("Initialize index: %s, type: %s",
                conf.getName(), conf.getType()));
        boolean mappingExists = false;
        boolean indexExists = getClient().admin().indices()
                .prepareExists(conf.getName()).execute().actionGet().isExists();
        if (indexExists) {
            if (!dropIfExists) {
                log.debug("Index " + conf.getName() + " already exists");
                mappingExists = getClient().admin().indices()
                        .prepareGetMappings(conf.getName()).execute()
                        .actionGet().getMappings().containsKey(DOC_TYPE);
            } else {
                log.warn(String.format("Initializing index: %s, type: %s with "
                        + "dropIfExists flag, deleting an existing index",
                        conf.getName(), conf.getType()));
                getClient().admin().indices()
                        .delete(new DeleteIndexRequest(conf.getName()))
                        .actionGet();
                indexExists = false;
            }
        }
        if (!indexExists) {
            log.info(String.format("Creating index: %s", conf.getName()));
            getClient().admin().indices().prepareCreate(conf.getName())
                    .setSettings(conf.getSettings()).execute().actionGet();
        }
        if (!mappingExists) {
            log.info(String.format("Creating mapping type: %s on index: %s",
                    conf.getType(), conf.getName()));
            getClient().admin().indices().preparePutMapping(conf.getName())
                    .setType(conf.getType()).setSource(conf.getMapping())
                    .execute().actionGet();

        }
    }

    @Override
    public void deactivate(ComponentContext context) throws Exception {
        super.deactivate(context);
        release();
    }

    protected void release() {
        if (client != null) {
            client.close();
        }
        if (localNode != null) {
            log.info("Shutting down local node");
            localNode.stop();
            localNode.close();
        }
        client = null;
        localNode = null;
        indexNames.clear();
    }

    protected String getWorkKey(DocumentModel doc) {
        return doc.getRepositoryName() + ":" + doc.getId();
    }

    @Override
    public boolean isAlreadyScheduledForIndexing(DocumentModel doc) {
        return pendingWork.contains(getWorkKey(doc));
    }

    @Override
    public int getPendingDocs() {
        return pendingWork.size();
    }

    @Override
    public int getPendingCommands() {
        return pendingCommands.size();
    }

    @Override public int getTotalCommandProcessed() {
        return totalCommandProcessed.get();
    }

    @Override
    public List<PendingClusterTask> getPendingTasks() {
        PendingClusterTasksResponse response = getClient().admin().cluster()
                .preparePendingClusterTasks().execute().actionGet();
        return response.getPendingTasks();
    }

    /**
     * Fetch document models from VCS, return results in the same order.
     *
     */
    protected List<DocumentModel> fetchDocuments(final List<String> ids,
            CoreSession session) throws ClientException {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM Document WHERE ecm:uuid IN (");
        for (int i = 0; i < ids.size(); i++) {
            sb.append(NXQL.escapeString(ids.get(i)));
            if (i < ids.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        DocumentModelList ret = session.query(sb.toString());
        // Order the results
        Collections.sort(ret, new Comparator<DocumentModel>() {
            @Override
            public int compare(DocumentModel a, DocumentModel b) {
                return ids.indexOf(a.getId()) - ids.indexOf(b.getId());
            }
        });
        return ret;
    }

}
