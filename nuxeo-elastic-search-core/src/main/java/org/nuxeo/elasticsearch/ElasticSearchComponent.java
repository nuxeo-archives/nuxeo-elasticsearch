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

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownRequest;
import org.elasticsearch.action.admin.cluster.tasks.PendingClusterTasksResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequestBuilder;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.service.PendingClusterTask;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.ImmutableSettings.Builder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermsFilterBuilder;
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
import org.nuxeo.ecm.core.event.impl.EventContextImpl;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.api.ElasticSearchIndexing;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.commands.IndexingCommand;
import org.nuxeo.elasticsearch.config.ElasticSearchIndex;
import org.nuxeo.elasticsearch.config.NuxeoElasticSearchConfig;
import org.nuxeo.elasticsearch.listener.EventConstants;
import org.nuxeo.elasticsearch.nxql.NXQLQueryConverter;
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

    protected static final Log log = LogFactory
            .getLog(ElasticSearchComponent.class);

    public static final String EP_Config = "elasticSearchConfig";

    public static final String EP_Index = "elasticSearchIndex";

    public static final String MAIN_IDX = "nxmain";

    public static final String NX_DOCUMENT = "doc";

    public static final String ID_FIELD = "_id";

    public static final String ACL_FIELD = "ecm:acl";

    public static final String[] DEFAULT_FULLTEXT_FIELDS = { "ecm:fulltext",
            "dc:title" };

    protected NuxeoElasticSearchConfig config;

    protected Node localNode;

    protected Client client;

    protected boolean indexInitDone = false;

    // indexing command that where received before the index initialization
    protected List<IndexingCommand> stackedCommands = new ArrayList<>();

    // temporary hack until we are able to list pending indexing jobs cluster
    // wide
    protected final CopyOnWriteArrayList<String> pendingWork = new CopyOnWriteArrayList<String>();

    protected final CopyOnWriteArrayList<String> pendingCommands = new CopyOnWriteArrayList<String>();

    protected Map<String, ElasticSearchIndex> indexes = new HashMap<String, ElasticSearchIndex>();

    protected List<String> fulltextFields;

    // Metrics
    protected final MetricRegistry registry = SharedMetricRegistries
            .getOrCreate(MetricsService.class.getName());

    protected Timer searchTimer;

    protected Timer fetchTimer;

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        if (EP_Config.equals(extensionPoint)) {
            release();
            config = (NuxeoElasticSearchConfig) contribution;
        } else if (EP_Index.equals(extensionPoint)) {
            ElasticSearchIndex idx = (ElasticSearchIndex) contribution;
            indexes.put(idx.getIndexName(), idx);
        }
    }

    @Override
    public NuxeoElasticSearchConfig getConfig() {
        if (Framework.isTestModeSet() && config == null) {
            // automatically generate test config
            config = new NuxeoElasticSearchConfig();
            config.setInProcess(true);
            config.enableHttp(true);
            File home = Framework.getRuntime().getHome();
            File esDirectory = new File(home, "elasticsearch");
            esDirectory.mkdir();
            config.setLogPath(esDirectory.getPath() + "/logs");
            config.setDataPath(esDirectory.getPath() + "/data");
            config.setIndexStorageType("memory");
        }
        return config;
    }

    protected Settings getSettings() {
        NuxeoElasticSearchConfig config = getConfig();
        Settings settings = null;
        String cname = config.getClusterName();
        if (config.isInProcess()) {
            if (cname == null) {
                cname = "NuxeoESCluster";
            }
            Builder sBuilder = ImmutableSettings.settingsBuilder();
            sBuilder.put("node.http.enabled", config.enableHttp())
                    .put("path.logs", config.getLogPath())
                    .put("path.data", config.getDataPath())
                    .put("index.number_of_shards", 1)
                    .put("index.number_of_replicas", 1)
                    .put("cluster.name", cname)
                    .put("node.name", config.getNodeName());
            if (config.getIndexStorageType() != null) {
                sBuilder.put("index.store.type", config.getIndexStorageType());
                if (config.getIndexStorageType().equals("memory")) {
                    sBuilder.put("gateway.type", "none");
                }
            }
            settings = sBuilder.build();
        } else {
            Builder builder = ImmutableSettings.settingsBuilder().put(
                    "node.http.enabled", config.enableHttp());
            if (cname != null) {
                builder.put("cluster.name", cname);
            }
            settings = builder.build();
        }
        return settings;
    }

    protected void schedulePostCommitIndexing(IndexingCommand cmd)
            throws ClientException {

        try {
            CoreSession session = cmd.getTargetDocument().getCoreSession();
            EventProducer evtProducer = Framework
                    .getLocalService(EventProducer.class);

            EventContextImpl context = new EventContextImpl(session,
                    session.getPrincipal());
            context.getProperties().put(cmd.getId(), cmd.toJSON());

            Event indexingEvent = context
                    .newEvent(EventConstants.ES_INDEX_EVENT_SYNC);
            evtProducer.fireEvent(indexingEvent);
        } catch (Exception e) {
            throw ClientException.wrap(e);
        }
    }

    @Override
    public void indexNow(List<IndexingCommand> cmds) throws ClientException {

        if (!indexInitDone) {
            stackedCommands.addAll(cmds);
            log.debug("Delaying indexing request : waiting for Index to be initialized");
            return;
        }

        BulkRequestBuilder bulkRequest = getClient().prepareBulk();
        for (IndexingCommand cmd : cmds) {
            if (IndexingCommand.DELETE.equals(cmd.getName())) {
                indexNow(cmd);
            } else {
                log.debug("Sending bulk indexing request to ElasticSearch "
                        + cmd.toString());
                IndexRequestBuilder idxRequest = buildESIndexingRequest(cmd);
                bulkRequest.add(idxRequest);
            }
        }
        // execute bulk index if any
        if (bulkRequest.numberOfActions() > 0) {
            if (log.isDebugEnabled()) {
                log.debug(String
                        .format("Index bulk request: curl -XPOST 'http://localhost:9200/_bulk' -d '%s'",
                                bulkRequest.request().requests().toString()));
            }
            bulkRequest.execute().actionGet();
        }

        for (IndexingCommand cmd : cmds) {
            markCommandExecuted(cmd);
        }
    }

    @Override
    public void indexNow(IndexingCommand cmd) throws ClientException {

        if (!indexInitDone) {
            stackedCommands.add(cmd);
            log.debug("Delaying indexing request : waiting for Index to be initialized");
            return;
        }

        log.debug("Sending indexing request to ElasticSearch " + cmd.toString());
        DocumentModel doc = cmd.getTargetDocument();
        if (IndexingCommand.DELETE.equals(cmd.getName())) {
            DeleteRequestBuilder request = getClient().prepareDelete(MAIN_IDX,
                    NX_DOCUMENT, doc.getId());
            if (log.isDebugEnabled()) {
                log.debug(String
                        .format("Delete request: curl -XDELETE 'http://localhost:9200/%s/%s/%s' -d '%s'",
                                MAIN_IDX, NX_DOCUMENT, cmd.getTargetDocument()
                                        .getId(), request.request().toString()));
            }
            request.execute().actionGet();

            if (cmd.isRecurse()) {
                DeleteByQueryRequestBuilder deleteRequest = getClient()
                        .prepareDeleteByQuery(MAIN_IDX).setQuery(
                                QueryBuilders.prefixQuery("ecm:path",
                                        doc.getPathAsString() + "/"));
                if (log.isDebugEnabled()) {
                    log.debug(String
                            .format("Delete byQuery request: curl -XDELETE 'http://localhost:9200/%s/%s/_query' -d '%s'",
                                    MAIN_IDX, NX_DOCUMENT, request.request()
                                            .toString()));
                }
                deleteRequest.execute().actionGet();
            }
        } else {
            IndexRequestBuilder request = buildESIndexingRequest(cmd);
            if (log.isDebugEnabled()) {
                log.debug(String
                        .format("Index request: curl -XPUT 'http://localhost:9200/%s/%s/%s' -d '%s'",
                                MAIN_IDX, NX_DOCUMENT, cmd.getTargetDocument()
                                        .getId(), request.request().toString()));
            }
            request.execute().actionGet();
        }
        markCommandExecuted(cmd);
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
            return getClient().prepareIndex(MAIN_IDX, NX_DOCUMENT, doc.getId())
                    .setSource(builder);
        } catch (Exception e) {
            throw new ClientException(
                    "Unable to create index request for Document "
                            + doc.getId(), e);
        }
    }

    protected void markCommandExecuted(IndexingCommand cmd) {
        pendingWork.remove(getWorkKey(cmd.getTargetDocument()));
        pendingCommands.remove(cmd.getId());
    }

    @Override
    public void scheduleIndexing(IndexingCommand cmd) throws ClientException {
        DocumentModel doc = cmd.getTargetDocument();
        boolean added = pendingCommands.addIfAbsent(cmd.getId());
        if (!added) {
            log.debug("Skip indexing for " + doc
                    + " since it is already scheduled");
            return;
        }

        added = pendingWork.addIfAbsent(getWorkKey(doc));
        if (!added) {
            log.debug("Skip indexing for " + doc
                    + " since it is already scheduled");
            return;
        }

        if (cmd.isSync()) {
            log.debug("Schedule PostCommit indexing request " + cmd.toString());
            schedulePostCommitIndexing(cmd);
        } else {
            log.debug("Schedule Async indexing request  " + cmd.toString());
            WorkManager wm = Framework.getLocalService(WorkManager.class);
            IndexingWorker idxWork = new IndexingWorker(cmd);
            wm.schedule(idxWork, true);
        }
    }

    @Override
    public void flush() {
        flush(false);
    }

    @Override
    public void flush(boolean commit) {
        // refresh indexes
        getClient().admin().indices().prepareRefresh(MAIN_IDX).execute()
                .actionGet();
        if (commit) {
            getClient().admin().indices().prepareFlush(MAIN_IDX).execute()
                    .actionGet();
        }
    }

    protected Node getLocalNode() {
        if (localNode == null) {
            if (log.isDebugEnabled()) {
                log.debug("Create a local ES node inside the Nuxeo JVM");
            }
            NuxeoElasticSearchConfig config = getConfig();
            Settings settings = getSettings();
            localNode = NodeBuilder.nodeBuilder().local(config.isInProcess())
                    .settings(settings).node();
            localNode.start();
        }
        return localNode;
    }

    @Override
    public Client getClient() {
        if (client == null) {
            if (getConfig().isInProcess()) {
                client = getLocalNode().client();
            } else {
                TransportClient tClient = new TransportClient(getSettings());
                for (String remoteNode : getConfig().getRemoteNodes()) {
                    String[] address = remoteNode.split(":");
                    try {
                        InetAddress inet = InetAddress.getByName(address[0]);
                        if (log.isDebugEnabled()) {
                            log.debug("Use a remote ES node: " + remoteNode);
                        }
                        tClient.addTransportAddress(new InetSocketTransportAddress(
                                inet, Integer.parseInt(address[1])));
                    } catch (UnknownHostException e) {
                        log.error("Unable to resolve host " + address[0], e);
                    }
                }
                client = tClient;
            }
        }
        return client;
    }

    @Override
    public DocumentModelList query(CoreSession session, String nxql, int limit,
            int offset, SortInfo... sortInfos) throws ClientException {
        QueryBuilder queryBuilder = NXQLQueryConverter.toESQueryBuilder(nxql,
                getFulltextFields());

        // handle the built-in order by clause
        if (nxql.toLowerCase().contains("order by")) {
            List<SortInfo> builtInSortInfos = NXQLQueryConverter
                    .getSortInfo(nxql);
            if (sortInfos != null) {
                for (SortInfo si : sortInfos) {
                    builtInSortInfos.add(si);
                }
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
            SearchRequestBuilder request = getClient().prepareSearch(MAIN_IDX)
                    .setTypes(NX_DOCUMENT)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .addField(ID_FIELD).setFrom(offset).setSize(limit);
            // Add security filter
            TermsFilterBuilder aclFilter = null;
            Principal principal = session.getPrincipal();
            if (principal != null) {
                if (!(principal instanceof NuxeoPrincipal && ((NuxeoPrincipal) principal)
                        .isAdministrator())) {
                    String[] principals = SecurityService
                            .getPrincipalsToCheck(principal);
                    if (principals.length > 0) {
                        aclFilter = FilterBuilders.inFilter(ACL_FIELD,
                                principals);
                    }
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
                                MAIN_IDX, NX_DOCUMENT, request.toString()));
            }
            SearchResponse response = request.execute().actionGet();
            if (log.isDebugEnabled()) {
                log.debug("Response: " + response.toString());
            }
            // Get the list of ids
            ids = new ArrayList<String>(limit - offset);
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
                    log.error(e);
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
        if (getConfig() == null) {
            log.warn("Unable to initialize ElasticSearch service : no configuration is provided");
            return;
        }
        // init metrics
        searchTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "search"));
        fetchTimer = registry.timer(MetricRegistry.name("nuxeo",
                "elasticsearch", "service", "fetch"));
        // start Server if needed
        if (getConfig() != null && !getConfig().isInProcess()
                && getConfig().autostartLocalNode()) {
            startESServer(getConfig());
        }
        // init client
        getClient();
        // init indexes if needed
        initIndexes(false);
    }

    protected void startESServer(NuxeoElasticSearchConfig config)
            throws Exception {
        ElasticSearchController controler = new ElasticSearchController(config);
        if (controler.start()) {
            log.info("Started Elastic Search");
        } else {
            log.error("Failed to start ElasticSearch");
        }
    }

    @Override
    public void initIndexes(boolean recreate) throws Exception {
        for (ElasticSearchIndex idx : indexes.values()) {
            initIndex(idx, recreate);
        }
        indexInitDone = true;
        if (stackedCommands.size() > 0) {
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
                    };
                }
            } catch (Exception e) {
                log.error("Unable to flush pending indexing commands", e);
            } finally {
                if (txCreated) {
                    TransactionHelper.commitOrRollbackTransaction();
                }
                stackedCommands.clear();
            }
        }
    }

    protected void initIndex(ElasticSearchIndex idxConfig, boolean recreate)
            throws Exception {

        log.info("Initialize index " + idxConfig.getIndexName());
        IndicesExistsRequestBuilder request = getClient().admin().indices()
                .prepareExists(idxConfig.getIndexName());
        IndicesExistsResponse exists = request.execute().actionGet();

        boolean indexExists = exists.isExists();
        boolean createIndex = idxConfig.mustCreate();

        if (!indexExists) {
            log.info("Index " + idxConfig.getIndexName()
                    + " NOT FOUND : will be created");
        } else {
            log.debug("Index " + idxConfig.getIndexName() + " already exists");
        }

        if (indexExists && recreate) {
            getClient().admin().indices()
                    .delete(new DeleteIndexRequest(idxConfig.getIndexName()))
                    .actionGet();
            indexExists = false;
            createIndex = true;
        }

        if (!indexExists && createIndex) {
            log.info("Create index " + idxConfig.getIndexName());
            // create index
            getClient().admin().indices()
                    .prepareCreate(idxConfig.getIndexName())
                    .setSettings(idxConfig.getSettings()).execute().actionGet();
            getClient().admin().indices()
                    .preparePutMapping(idxConfig.getIndexName())
                    .setType(NX_DOCUMENT).setSource(idxConfig.getMapping())
                    .execute().actionGet();
        }

        if (idxConfig.forceUpdate()) {
            log.info("Update index config" + idxConfig.getIndexName());
            // update settings
            getClient().admin().indices()
                    .prepareUpdateSettings(idxConfig.getIndexName())
                    .setSettings(idxConfig.getSettings()).execute().actionGet();

            // update mapping
            getClient().admin().indices()
                    .preparePutMapping(idxConfig.getIndexName())
                    .setSource(idxConfig.getMapping());
        }
    }

    @Override
    public void deactivate(ComponentContext context) throws Exception {
        super.deactivate(context);
        release();
    }

    protected void release() {
        if (client != null) {
            if (getConfig() != null && !getConfig().isInProcess()
                    && getConfig().autostartLocalNode()) {
                client.admin()
                        .cluster()
                        .nodesShutdown(
                                new NodesShutdownRequest(getConfig()
                                        .getNodeName())).actionGet();
            }
            client.close();
        }
        if (localNode != null) {
            localNode.stop();
            localNode.close();
        }
        client = null;
        localNode = null;
    }

    protected String getWorkKey(DocumentModel doc) {
        return doc.getRepositoryName() + ":" + doc.getId();
    }

    @Override
    public boolean isAlreadyScheduledForIndexing(DocumentModel doc) {
        if (pendingWork.contains(getWorkKey(doc))) {
            return true;
        }
        return false;
    }

    @Override
    public int getPendingDocs() {
        return pendingWork.size();
    }

    @Override
    public int getPendingCommands() {
        return pendingCommands.size();
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

    @Override
    public List<String> getFulltextFields() {
        if (fulltextFields != null) {
            return fulltextFields;
        }
        ElasticSearchIndex idxConfig = indexes.get(MAIN_IDX);
        if (idxConfig != null && (!idxConfig.getFulltextFields().isEmpty())) {
            fulltextFields = idxConfig.getFulltextFields();
        } else {
            fulltextFields = Arrays.asList(DEFAULT_FULLTEXT_FIELDS);
        }
        return fulltextFields;
    }

}
