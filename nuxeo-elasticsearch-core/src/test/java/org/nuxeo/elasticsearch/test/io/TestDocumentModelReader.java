package org.nuxeo.elasticsearch.test.io;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.concordion.internal.command.AssertEqualsCommand;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.collections.ArrayMap;
import org.nuxeo.ecm.automation.jaxrs.io.documents.JsonESDocumentWriter;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.io.DocumentModelReaders;
import org.nuxeo.elasticsearch.query.NxQueryBuilder;
import org.nuxeo.elasticsearch.test.BareElasticSearchFeature;
import org.nuxeo.elasticsearch.test.RepositoryElasticSearchFeature;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import com.google.inject.Inject;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * @since 5.9.5
 */

@RunWith(FeaturesRunner.class)
@Features({ RepositoryElasticSearchFeature.class })
@LocalDeploy("org.nuxeo.elasticsearch.core:elasticsearch-test-contrib.xml")
public class TestDocumentModelReader {
    private static final String IDX_NAME = "nxutest";

    private static final String TYPE_NAME = "doc";

    @Inject
    protected CoreSession session;

    @Inject
    ElasticSearchAdmin esa;

    @After
    public void cleanupIndexed() throws Exception {
        esa.initIndexes(true);
    }

    @Test
    public void ICanReadADocModelFromJson() throws Exception {
        String json = "{\"ecm:versionLabel\":\"0.0\",\"common:icon-expanded\":null,\"common:size\":null," +
                "\"ecm:currentLifeCycleState\":\"project\",\"ecm:changeToken\":null,\"ecm:uuid\":\"56ca3935-c6c9-4cd4-ac23-d9df5ebf340a\"," +
                "\"dc:nature\":\"Nature0\",\"dc:created\":null,\"relatedtext:relatedtextresources\":[],\"dc:description\":null," +
                "\"dc:rights\":\"Rights0\",\"file:content\":null,\"uid:uid\":null,\"files:files\":[]," +
                "\"ecm:acl\":[\"administrators\",\"Administrator\",\"members\"],\"dc:subjects\":[],\"file:filename\":null," +
                "\"dc:format\":null,\"dc:valid\":null,\"ecm:path\":\"/root/my/path/file0\"," +
                "\"ecm:mixinType\":[\"Downloadable\",\"Commentable\",\"Versionable\",\"Publishable\",\"HasRelatedText\"]," +
                "\"ecm:isProxy\":false,\"ecm:isCheckedIn\":false," +
                "\"dc:title\":\"File Title\",\"dc:lastContributor\":null," +
                "\"ecm:repository\":\"test\",\"common:icon\":null,\"dc:creator\":null," +
                "\"ecm:primaryType\":\"File\",\"dc:contributors\":[],\"dc:source\":null," +
                "\"ecm:name\":\"file0\",\"dc:publisher\":null,\"uid:major_version\":\"0\",\"ecm:parentId\":\"35cef677-f721-47b8-ab6b-050dbe257d0d\"," +
                "\"ecm:isVersion\":false,\"uid:minor_version\":\"0\",\"dc:issued\":null," +
                "\"ecm:title\":\"File Title\",\"dc:modified\":null,\"dc:expired\":null,\"dc:coverage\":null,\"dc:language\":null}";
        DocumentModel doc = DocumentModelReaders.fromJson(json).getDocumentModel();
        Assert.assertNotNull(doc);
        Assert.assertEquals("56ca3935-c6c9-4cd4-ac23-d9df5ebf340a", doc.getId());
        Assert.assertEquals("project", doc.getCurrentLifeCycleState());
        Assert.assertEquals("file0", doc.getName());
        Assert.assertEquals("/root/my/path/file0", doc.getPathAsString());
        Assert.assertEquals("test", doc.getRepositoryName());
        Assert.assertNull(doc.getSessionId());
        Assert.assertFalse(doc.isProxy());
        Assert.assertFalse(doc.isFolder());
        Assert.assertFalse(doc.isVersion());
        Assert.assertFalse(doc.isLocked());
        Assert.assertEquals("File Title", doc.getTitle());
        // Assert.assertEquals("Failure", doc.getLifeCyclePolicy());
        Assert.assertNotNull(doc.getParentRef());
        Assert.assertTrue(doc.isImmutable());
        Assert.assertEquals("File", doc.getType());
    }


    @Test
    public void ICanReadADocModelFromSource() throws Exception {
        Map<String, Object> source = new HashMap<String, Object>();
        source.put("ecm:uuid", "001");
        source.put("ecm:primaryType", "File");
        DocumentModel doc = DocumentModelReaders.fromSource(source).getDocumentModel();
        Assert.assertNotNull(doc);
        Assert.assertEquals(doc.getId(), "001");
        Assert.assertEquals("File", doc.getType());
        Assert.assertFalse(doc.isFolder());
    }


    @Test
    public void IBehaveLikeVcs() throws Exception {
        // I create a document
        DocumentModel doc = session.createDocumentModel("/", "somefile", "File");
        doc.setPropertyValue("dc:title", "Some file");
        doc = session.createDocument(doc);
        session.save();
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        WorkManager wm = Framework.getLocalService(WorkManager.class);
        Assert.assertTrue(wm.awaitCompletion(20, TimeUnit.SECONDS));
        esa.refresh();

        // search and retrieve from ES
        ElasticSearchService ess = Framework.getLocalService(ElasticSearchService.class);
        DocumentModelList docs = ess
                .query(new NxQueryBuilder(session).nxql("SELECT * FROM File")
                        .fetchFromElasticsearch());
        Assert.assertEquals(1, docs.totalSize());
        DocumentModel esDoc = docs.get(0);
        // esDoc.detach(false);
        Assert.assertNotNull(esDoc);

        // search from ES retrieve with VCS
        docs = ess
                .query(new NxQueryBuilder(session).nxql("SELECT * FROM File")
                        .fetchFromDatabase());
        DocumentModel vcsDoc = docs.get(0);

        // compare both docs
        Assert.assertNotNull(esDoc);
        Assert.assertEquals(vcsDoc, esDoc);

        JsonFactory factory = new JsonFactory();
        OutputStream out = new ByteArrayOutputStream();
        JsonGenerator jsonGen = factory.createJsonGenerator(out);
        JsonESDocumentWriter.writeESDocument(jsonGen, esDoc,
                    null, null);
        String esJson = out.toString();

        out = new ByteArrayOutputStream();
        jsonGen = factory.createJsonGenerator(out);
        JsonESDocumentWriter.writeESDocument(jsonGen, vcsDoc,
                null, null);
        String vcsJson = out.toString();

        Assert.assertEquals(vcsJson, esJson);
        }
    }
