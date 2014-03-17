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
 *     Nuxeo
 */
package org.nuxeo.elasticsearch.commands;

import java.io.IOException;
import java.io.StringWriter;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;


/**
 *
 * @author <a href="mailto:tdelprat@nuxeo.com">Tiry</a>
 *
 */
public class IndexingCommand {

    public static final String INDEX = "ESIndex";

    public static final String UPDATE = "ESReIndex";

    public static final String UPDATE_SECURITY = "ESReIndexSecurity";

    public static final String DELETE = "ESUnIndex";

    protected String name;

    protected boolean sync;

    protected boolean recurse;

    protected DocumentModel targetDocument;

    protected String id;

    protected static final Log log = LogFactory.getLog(IndexingCommand.class);

    public static final String PREFIX = "IndexingCommand-";

    protected IndexingCommand() {
        //
    }

    public IndexingCommand(DocumentModel targetDocument,String command, boolean sync, boolean recurse) {
        this.id = PREFIX + UUID.randomUUID().toString();
        this.name=command;
        this.sync=sync;
        this.recurse=recurse;
        this.targetDocument=targetDocument;
    }

    public IndexingCommand(DocumentModel targetDocument, boolean sync, boolean recurse) {
        this(targetDocument, INDEX, sync, recurse);
    }

    public void refresh(CoreSession session) throws ClientException {
        targetDocument = session.getDocument(targetDocument.getRef());
    }
    public void update(IndexingCommand other) {
        update(other.sync, other.recurse);
    }
    public void update(boolean sync, boolean recurse) {
        this.sync = this.sync || sync;
        this.recurse = this.recurse || recurse;
    }

    public boolean isSync() {
        return sync;
    }

    public boolean isRecurse() {
        return recurse;
    }

    public String getName() {
        return name;
    }

    public DocumentModel getTargetDocument() {
        return targetDocument;
    }

    public String toJSON() throws IOException {
        StringWriter out = new StringWriter();
        JsonFactory factory = new JsonFactory();
        JsonGenerator jsonGen = factory.createJsonGenerator(out);
        toJSON(jsonGen);
        out.flush();
        jsonGen.close();
        return out.toString();
    }

    public void toJSON(JsonGenerator jsonGen) throws IOException {
        jsonGen.writeStartObject();
        jsonGen.writeStringField("id", id);
        jsonGen.writeStringField("name", name);
        jsonGen.writeStringField("docId", targetDocument.getId());
        jsonGen.writeStringField("repo", targetDocument.getRepositoryName());
        jsonGen.writeBooleanField("recurse", recurse);
        jsonGen.writeBooleanField("sync", sync);
        jsonGen.writeEndObject();
    }

    public static IndexingCommand fromJSON(CoreSession session, String json) throws ClientException {
        JsonFactory jsonFactory = new JsonFactory();
        try {
            JsonParser jp = jsonFactory.createJsonParser(json);
            try {
                return fromJSON(session, jp);
            } finally {
                jp.close();
            }
        } catch (Exception e) {
            throw ClientException.wrap(e);
        }
    }

    public static IndexingCommand fromJSON(CoreSession session, JsonParser jp) throws Exception {

        IndexingCommand cmd = new IndexingCommand();
        String docId=null;
        String repo=null;

        JsonToken token = jp.nextToken(); // will return JsonToken.START_OBJECT (verify?)
        if (token != JsonToken.START_OBJECT) {
            return  null;
        }
        while (jp.nextToken() != JsonToken.END_OBJECT) {
          String fieldname = jp.getCurrentName();
          jp.nextToken(); // move to value, or START_OBJECT/START_ARRAY
          if ("name".equals(fieldname)) { // contains an object
              cmd.name=jp.getText();
          } else if ("docId".equals(fieldname)) {
            docId = jp.getText();
          } else if ("repo".equals(fieldname)) {
              repo = jp.getText();
          } else if ("id".equals(fieldname)) {
              cmd.id = jp.getText();
          } else if ("recurse".equals(fieldname)) {
              cmd.recurse = jp.getBooleanValue();
          } else if ("sync".equals(fieldname)) {
              cmd.recurse = jp.getBooleanValue();
          }
        }
        if (docId!=null) {
            if (!session.getRepositoryName().equals(repo)) {
                log.error("Unable to restore doc from repository " + repo + " with a session on repository " + session.getRepositoryName());
            } else {
                IdRef ref = new IdRef(docId);
                if (! session.exists(ref)) {
                    log.error("Unable to restore doc from repository with uid " + docId);
                } else {
                    cmd.targetDocument= session.getDocument(ref);
                }
            }
        }

       return cmd;
    }

    public String getId() {
        return id;
    }

    public IndexingCommand clone(DocumentModel newDoc) {
        return new IndexingCommand(newDoc, name, sync, recurse);
    }

}
