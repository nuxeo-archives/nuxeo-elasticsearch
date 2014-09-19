/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     bdelbosc
 */

package org.nuxeo.elasticsearch.provider;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.index.query.QueryBuilder;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.ClientRuntimeException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.SortInfo;
import org.nuxeo.ecm.platform.query.api.AbstractPageProvider;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.elasticsearch.aggregate.AggregateEsBase;
import org.nuxeo.elasticsearch.aggregate.AggregateFactory;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.api.EsResult;
import org.nuxeo.elasticsearch.query.NxQueryBuilder;
import org.nuxeo.elasticsearch.query.PageProviderQueryBuilder;
import org.nuxeo.runtime.api.Framework;

public class ElasticSearchNativePageProvider extends
        AbstractPageProvider<DocumentModel> {

    public static final String CORE_SESSION_PROPERTY = "coreSession";
    public static final String SEARCH_ON_ALL_REPOSITORIES_PROPERTY = "searchAllRepositories";
    protected static final Log log = LogFactory
            .getLog(ElasticSearchNativePageProvider.class);
    private static final long serialVersionUID = 1L;
    protected List<DocumentModel> currentPageDocuments;

    protected HashMap<String, Aggregate> currentAggregates;

    @Override
    public Map<String, Aggregate> getAggregates() {
        getCurrentPage();
        return currentAggregates;
    }

    @Override
    public List<DocumentModel> getCurrentPage() {
        // use a cache
        if (currentPageDocuments != null) {
            return currentPageDocuments;
        }
        if (log.isDebugEnabled()) {
            log.debug(String
                    .format("Perform query for provider '%s': with pageSize=%d, offset=%d",
                            getName(), getMinMaxPageSize(),
                            getCurrentPageOffset()));
        }
        // Build the ES query
        QueryBuilder query = makeQueryBuilder();
        SortInfo[] sortArray = null;
        if (sortInfos != null) {
            sortArray = sortInfos.toArray(new SortInfo[sortInfos.size()]);
        }
        // Execute the ES query
        ElasticSearchService ess = Framework
                .getLocalService(ElasticSearchService.class);
        try {
            NxQueryBuilder nxQuery = new NxQueryBuilder(getCoreSession())
                    .esQuery(query).offset((int) getCurrentPageOffset())
                    .limit((int) getMinMaxPageSize()).addSort(sortArray)
                    .addAggregates(buildAggregates());
            if (searchOnAllRepositories()) {
                nxQuery.searchOnAllRepositories();
            }
            EsResult ret = ess.queryAndAggregate(nxQuery);
            DocumentModelList dmList = ret.getDocuments();
            currentAggregates = new HashMap<String, Aggregate>(ret.getAggregates().size());
            for (Aggregate agg : ret.getAggregates()) {
                currentAggregates.put(agg.getId(), agg);
            }
            setResultsCount(dmList.totalSize());
            currentPageDocuments = dmList;
        } catch (ClientException e) {
            throw new ClientRuntimeException(e);
        }
        return currentPageDocuments;
    }

    private List<AggregateEsBase<? extends Bucket>> buildAggregates() {
        ArrayList<AggregateEsBase<? extends Bucket>> ret = new ArrayList<AggregateEsBase<? extends Bucket>>(
                getAggregateDefinitions().size());
        for (AggregateDefinition def : getAggregateDefinitions()) {
            ret.add(AggregateFactory.create(def, getSearchDocumentModel()));
        }
        return ret;
    }

    @Override
    public boolean hasAggregateSupport() {
        return true;
    }

    protected QueryBuilder makeQueryBuilder() {
        QueryBuilder ret;
        try {
            PageProviderDefinition def = getDefinition();
            if (def.getWhereClause() == null) {
                ret = PageProviderQueryBuilder.makeQuery(def.getPattern(),
                        getParameters(), def.getQuotePatternParameters(),
                        def.getEscapePatternParameters(), isNativeQuery());
            } else {
                DocumentModel searchDocumentModel = getSearchDocumentModel();
                if (searchDocumentModel == null) {
                    throw new ClientException(String.format(
                            "Cannot build query of provider '%s': "
                                    + "no search document model is set",
                            getName()));
                }
                ret = PageProviderQueryBuilder.makeQuery(searchDocumentModel,
                        def.getWhereClause(), getParameters(), isNativeQuery());
            }
        } catch (ClientException e) {
            throw new ClientRuntimeException(e);
        }
        return ret;
    }

    @Override
    protected void pageChanged() {
        currentPageDocuments = null;
        currentAggregates = null;
        super.pageChanged();
    }

    @Override
    public void refresh() {
        currentPageDocuments = null;
        currentAggregates = null;
        super.refresh();
    }

    protected CoreSession getCoreSession() {
        Map<String, Serializable> props = getProperties();
        CoreSession coreSession = (CoreSession) props
                .get(CORE_SESSION_PROPERTY);
        if (coreSession == null) {
            throw new ClientRuntimeException("cannot find core session");
        }
        return coreSession;
    }

    protected boolean searchOnAllRepositories() {
        String value = (String) getProperties().get(
                SEARCH_ON_ALL_REPOSITORIES_PROPERTY);
        if (value == null) {
            return false;
        }
        return Boolean.valueOf(value);
    }

    public boolean isNativeQuery() {
        return true;
    }
}
