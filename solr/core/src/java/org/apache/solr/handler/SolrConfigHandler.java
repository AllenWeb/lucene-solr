package org.apache.solr.handler;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableSet;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.ZkSolrResourceLoader;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.ConfigOverlay;
import org.apache.solr.core.ImplicitPlugins;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.RequestParams;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.SchemaManager;
import org.apache.solr.util.CommandOperation;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singletonList;
import static org.apache.solr.common.cloud.ZkNodeProps.makeMap;
import static org.apache.solr.common.params.CoreAdminParams.NAME;
import static org.apache.solr.common.util.StrUtils.formatString;
import static org.apache.solr.core.ConfigOverlay.NOT_EDITABLE;
import static org.apache.solr.core.ConfigOverlay.ZNODEVER;
import static org.apache.solr.core.SolrConfig.PluginOpts.REQUIRE_CLASS;
import static org.apache.solr.core.SolrConfig.PluginOpts.REQUIRE_NAME;
import static org.apache.solr.core.SolrConfig.PluginOpts.REQUIRE_NAME_IN_OVERLAY;
import static org.apache.solr.schema.FieldType.CLASS_NAME;

public class SolrConfigHandler extends RequestHandlerBase {
  public static final Logger log = LoggerFactory.getLogger(SolrConfigHandler.class);
  public static final boolean configEditing_disabled = Boolean.getBoolean("disable.configEdit");
  private static final Map<String, SolrConfig.SolrPluginInfo> namedPlugins;
  private Lock reloadLock = new ReentrantLock(true);

  static {
    Map<String, SolrConfig.SolrPluginInfo> map = new HashMap<>();
    for (SolrConfig.SolrPluginInfo plugin : SolrConfig.plugins) {
      if (plugin.options.contains(REQUIRE_NAME) || plugin.options.contains(REQUIRE_NAME_IN_OVERLAY)) {
        map.put(plugin.getCleanTag().toLowerCase(Locale.ROOT), plugin);
      }
    }
    namedPlugins = Collections.unmodifiableMap(map);
  }


  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {

    setWt(req, CommonParams.JSON);
    String httpMethod = (String) req.getContext().get("httpMethod");
    Command command = new Command(req, rsp, httpMethod);
    if ("POST".equals(httpMethod)) {
      if (configEditing_disabled)
        throw new SolrException(SolrException.ErrorCode.FORBIDDEN, " solrconfig editing is not enabled");
      try {
        command.handlePOST();
      } finally {
        RequestHandlerUtils.addExperimentalFormatWarning(rsp);
      }
    } else {
      command.handleGET();
    }
  }


  private class Command {
    private final SolrQueryRequest req;
    private final SolrQueryResponse resp;
    private final String method;
    private String path;
    List<String> parts;

    private Command(SolrQueryRequest req, SolrQueryResponse resp, String httpMethod) {
      this.req = req;
      this.resp = resp;
      this.method = httpMethod;
      path = (String) req.getContext().get("path");
      if (path == null) path = getDefaultPath();
      parts = StrUtils.splitSmart(path, '/');
      if (parts.get(0).isEmpty()) parts.remove(0);
    }

    private String getDefaultPath() {
      return "/config";
    }

    private void handleGET() {
      if (parts.size() == 1) {
        //this is the whole config. sent out the whole payload
        resp.add("config", getConfigDetails());
      } else {
        if (ConfigOverlay.NAME.equals(parts.get(1))) {
          resp.add(ConfigOverlay.NAME, req.getCore().getSolrConfig().getOverlay().toMap());
        } else if (RequestParams.NAME.equals(parts.get(1))) {
          if (parts.size() == 3) {
            RequestParams params = req.getCore().getSolrConfig().getRequestParams();
            MapSolrParams p = params.getParams(parts.get(2));
            Map m = new LinkedHashMap<>();
            m.put(ZNODEVER, params.getZnodeVersion());
            if (p != null) {
              m.put(RequestParams.NAME, makeMap(parts.get(2), p.getMap()));
            }
            resp.add(SolrQueryResponse.NAME, m);
          } else {
            resp.add(SolrQueryResponse.NAME, req.getCore().getSolrConfig().getRequestParams().toMap());
          }

        } else {
          if (ZNODEVER.equals(parts.get(1))) {
            resp.add(ZNODEVER, ZkNodeProps.makeMap(
                ConfigOverlay.NAME, req.getCore().getSolrConfig().getOverlay().getZnodeVersion(),
                RequestParams.NAME, req.getCore().getSolrConfig().getRequestParams().getZnodeVersion()));
            boolean checkStale = false;
            int expectedVersion = req.getParams().getInt(ConfigOverlay.NAME, -1);
            int actualVersion = req.getCore().getSolrConfig().getOverlay().getZnodeVersion();
            if (expectedVersion > actualVersion) {
              log.info("expecting overlay version {} but my version is {}", expectedVersion, actualVersion);
              checkStale = true;
            } else if (expectedVersion != -1) {
              log.info("I already have the expected version {} of config", expectedVersion);
            }
            expectedVersion = req.getParams().getInt(RequestParams.NAME, -1);
            actualVersion = req.getCore().getSolrConfig().getRequestParams().getZnodeVersion();
            if (expectedVersion > actualVersion) {
              log.info("expecting params version {} but my version is {}", expectedVersion, actualVersion);
              checkStale = true;
            } else if (expectedVersion != -1) {
              log.info("I already have the expected version {} of params", expectedVersion);
            }
            if (checkStale && req.getCore().getResourceLoader() instanceof ZkSolrResourceLoader) {
              new Thread(SolrConfigHandler.class.getSimpleName() + "-refreshconf") {
                @Override
                public void run() {
                  if (!reloadLock.tryLock()) {
                    log.info("Another reload is in progress . Not doing anything");
                    return;
                  }
                  try {
                    log.info("Trying to update my configs");
                    SolrCore.getConfListener(req.getCore(), (ZkSolrResourceLoader) req.getCore().getResourceLoader()).run();
                  } catch (Exception e) {
                    log.error("Unable to refresh conf ", e);
                  } finally {
                    reloadLock.unlock();
                  }
                }
              }.start();
            } else {
              log.info("checkStale {} , resourceloader {}", checkStale, req.getCore().getResourceLoader().getClass().getName());
            }

          } else {
            Map<String, Object> m = getConfigDetails();
            resp.add("config", makeMap(parts.get(1), m.get(parts.get(1))));
          }
        }
      }
    }

    private Map<String, Object> getConfigDetails() {
      Map<String, Object> map = req.getCore().getSolrConfig().toMap();
      Map reqHandlers = (Map) map.get(SolrRequestHandler.TYPE);
      if (reqHandlers == null) map.put(SolrRequestHandler.TYPE, reqHandlers = new LinkedHashMap<>());
      List<PluginInfo> plugins = ImplicitPlugins.getHandlers(req.getCore());
      for (PluginInfo plugin : plugins) {
        if (SolrRequestHandler.TYPE.equals(plugin.type)) {
          if (!reqHandlers.containsKey(plugin.name)) {
            reqHandlers.put(plugin.name, plugin.toMap());
          }
        }
      }
      return map;
    }


    private void handlePOST() throws IOException {
      Iterable<ContentStream> streams = req.getContentStreams();
      if (streams == null) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "missing content stream");
      }
      ArrayList<CommandOperation> ops = new ArrayList<>();

      for (ContentStream stream : streams)
        ops.addAll(CommandOperation.parse(stream.getReader()));
      List<Map> errList = CommandOperation.captureErrors(ops);
      if (!errList.isEmpty()) {
        resp.add(CommandOperation.ERR_MSGS, errList);
        return;
      }

      try {
        for (; ; ) {
          ArrayList<CommandOperation> opsCopy = new ArrayList<>(ops.size());
          for (CommandOperation op : ops) opsCopy.add(op.getCopy());
          try {
            if (parts.size() > 1 && RequestParams.NAME.equals(parts.get(1))) {
              RequestParams params = RequestParams.getFreshRequestParams(req.getCore().getResourceLoader(), req.getCore().getSolrConfig().getRequestParams());
              handleParams(opsCopy, params);
            } else {
              ConfigOverlay overlay = SolrConfig.getConfigOverlay(req.getCore().getResourceLoader());
              handleCommands(opsCopy, overlay);
            }
            break;//succeeded . so no need to go over the loop again
          } catch (ZkController.ResourceModifiedInZkException e) {
            //retry
            log.info("Race condition, the node is modified in ZK by someone else " + e.getMessage());
          }
        }
      } catch (Exception e) {
        resp.setException(e);
        resp.add(CommandOperation.ERR_MSGS, singletonList(SchemaManager.getErrorStr(e)));
      }

    }


    private void handleParams(ArrayList<CommandOperation> ops, RequestParams params) {
      for (CommandOperation op : ops) {
        switch (op.name) {
          case SET:
          case UPDATE: {
            Map<String, Object> map = op.getDataMap();
            if (op.hasError()) break;

            for (Map.Entry<String, Object> entry : map.entrySet()) {

              Map val = null;
              String key = entry.getKey();
              if (key == null || key.trim().isEmpty()) {
                op.addError("null key ");
                continue;
              }
              key = key.trim();
              String err = validateName(key);
              if (err != null) {
                op.addError(err);
                continue;
              }

              try {
                val = (Map) entry.getValue();
              } catch (Exception e1) {
                op.addError("invalid params for key : " + key);
                continue;
              }

              if (val.containsKey("")) {
                op.addError("Empty keys are not allowed in params");
                continue;
              }

              MapSolrParams old = params.getParams(key);
              if (op.name.equals(UPDATE)) {
                LinkedHashMap m = new LinkedHashMap(old.getMap());
                m.putAll(val);
                val = m;
              }
              params = params.setParams(key, val);

            }
            break;

          }
          case "delete": {
            List<String> name = op.getStrs(CommandOperation.ROOT_OBJ);
            if (op.hasError()) break;
            for (String s : name) {
              if (params.getParams(s) == null) {
                op.addError(formatString("can't delete . No such params ''{0}'' exist", s));
              }
              params = params.setParams(s, null);
            }
          }
        }
      }


      List errs = CommandOperation.captureErrors(ops);
      if (!errs.isEmpty()) {
        resp.add(CommandOperation.ERR_MSGS, errs);
        return;
      }

      SolrResourceLoader loader = req.getCore().getResourceLoader();
      if (loader instanceof ZkSolrResourceLoader) {
        ZkSolrResourceLoader zkLoader = (ZkSolrResourceLoader) loader;
        if (ops.isEmpty()) {
          ZkController.touchConfDir(zkLoader);
        } else {
          log.info("persisting params version : {}", params.toMap());
          int latestVersion = ZkController.persistConfigResourceToZooKeeper(zkLoader,
              params.getZnodeVersion(),
              RequestParams.RESOURCE,
              params.toByteArray(), true);
          waitForAllReplicasState(req.getCore().getCoreDescriptor().getCloudDescriptor().getCollectionName(),
              req.getCore().getCoreDescriptor().getCoreContainer().getZkController(),
              RequestParams.NAME,
              latestVersion, 30);
        }

      } else {
        SolrResourceLoader.persistConfLocally(loader, RequestParams.RESOURCE, params.toByteArray());
        req.getCore().getSolrConfig().refreshRequestParams();
      }

    }

    private void handleCommands(List<CommandOperation> ops, ConfigOverlay overlay) throws IOException {
      for (CommandOperation op : ops) {
        switch (op.name) {
          case SET_PROPERTY:
            overlay = applySetProp(op, overlay);
            break;
          case UNSET_PROPERTY:
            overlay = applyUnset(op, overlay);
            break;
          case SET_USER_PROPERTY:
            overlay = applySetUserProp(op, overlay);
            break;
          case UNSET_USER_PROPERTY:
            overlay = applyUnsetUserProp(op, overlay);
            break;
          default: {
            List<String> pcs = StrUtils.splitSmart(op.name.toLowerCase(Locale.ROOT), '-');
            if (pcs.size() != 2) {
              op.addError(formatString("Unknown operation ''{0}'' ", op.name));
            } else {
              String prefix = pcs.get(0);
              String name = pcs.get(1);
              if (cmdPrefixes.contains(prefix) && namedPlugins.containsKey(name)) {
                SolrConfig.SolrPluginInfo info = namedPlugins.get(name);
                if ("delete".equals(prefix)) {
                  overlay = deleteNamedComponent(op, overlay, info.getCleanTag());
                } else {
                  overlay = updateNamedPlugin(info, op, overlay, prefix.equals("create") || prefix.equals("add"));
                }
              } else {
                op.addError(formatString("Unknown operation ''{0}'' ", op.name));
              }
            }
          }
        }
      }
      List errs = CommandOperation.captureErrors(ops);
      if (!errs.isEmpty()) {
        log.info("Failed to run commands. errors are {}", StrUtils.join(errs, ','));
        resp.add(CommandOperation.ERR_MSGS, errs);
        return;
      }

      SolrResourceLoader loader = req.getCore().getResourceLoader();
      if (loader instanceof ZkSolrResourceLoader) {
        int latestVersion = ZkController.persistConfigResourceToZooKeeper((ZkSolrResourceLoader) loader, overlay.getZnodeVersion(),
            ConfigOverlay.RESOURCE_NAME, overlay.toByteArray(), true);
        log.info("Executed config commands successfully and persisted to ZK {}", ops);
        waitForAllReplicasState(req.getCore().getCoreDescriptor().getCloudDescriptor().getCollectionName(),
            req.getCore().getCoreDescriptor().getCoreContainer().getZkController(),
            ConfigOverlay.NAME,
            latestVersion, 30);
      } else {
        SolrResourceLoader.persistConfLocally(loader, ConfigOverlay.RESOURCE_NAME, overlay.toByteArray());
        req.getCore().getCoreDescriptor().getCoreContainer().reload(req.getCore().getName());
        log.info("Executed config commands successfully and persited to File System {}", ops);
      }

    }

    private ConfigOverlay deleteNamedComponent(CommandOperation op, ConfigOverlay overlay, String typ) {
      String name = op.getStr(CommandOperation.ROOT_OBJ);
      if (op.hasError()) return overlay;
      if (overlay.getNamedPlugins(typ).containsKey(name)) {
        return overlay.deleteNamedPlugin(name, typ);
      } else {
        op.addError(formatString("NO such {0} ''{1}'' ", typ, name));
        return overlay;
      }
    }

    private ConfigOverlay updateNamedPlugin(SolrConfig.SolrPluginInfo info, CommandOperation op, ConfigOverlay overlay, boolean isCeate) {
      String name = op.getStr(NAME);
      String clz = info.options.contains(REQUIRE_CLASS) ? op.getStr(CLASS_NAME) : op.getStr(CLASS_NAME, null);
      op.getMap(PluginInfo.DEFAULTS, null);
      op.getMap(PluginInfo.INVARIANTS, null);
      op.getMap(PluginInfo.APPENDS, null);
      if (op.hasError()) return overlay;
      if (!verifyClass(op, clz, info.clazz)) return overlay;
      if (overlay.getNamedPlugins(info.getCleanTag()).containsKey(name)) {
        if (isCeate) {
          op.addError(formatString(" ''{0}'' already exists . Do an ''{1}'' , if you want to change it ", name, "update-" + info.getTagCleanLower()));
          return overlay;
        } else {
          return overlay.addNamedPlugin(op.getDataMap(), info.getCleanTag());
        }
      } else {
        if (isCeate) {
          return overlay.addNamedPlugin(op.getDataMap(), info.getCleanTag());
        } else {
          op.addError(formatString(" ''{0}'' does not exist . Do an ''{1}'' , if you want to create it ", name, "create-" + info.getTagCleanLower()));
          return overlay;
        }
      }
    }

    private boolean verifyClass(CommandOperation op, String clz, Class expected) {
      if (clz == null) return true;
      if (!"true".equals(String.valueOf(op.getStr("runtimeLib", null)))) {
        //this is not dynamically loaded so we can verify the class right away
        try {
          req.getCore().createInitInstance(new PluginInfo(SolrRequestHandler.TYPE, op.getDataMap()), expected, clz, "");
        } catch (Exception e) {
          op.addError(e.getMessage());
          return false;
        }

      }
      return true;
    }

    private ConfigOverlay applySetUserProp(CommandOperation op, ConfigOverlay overlay) {
      Map<String, Object> m = op.getDataMap();
      if (op.hasError()) return overlay;
      for (Map.Entry<String, Object> e : m.entrySet()) {
        String name = e.getKey();
        Object val = e.getValue();
        overlay = overlay.setUserProperty(name, val);
      }
      return overlay;
    }

    private ConfigOverlay applyUnsetUserProp(CommandOperation op, ConfigOverlay overlay) {
      List<String> name = op.getStrs(CommandOperation.ROOT_OBJ);
      if (op.hasError()) return overlay;
      for (String o : name) {
        if (!overlay.getUserProps().containsKey(o)) {
          op.addError(formatString("No such property ''{0}''", name));
        } else {
          overlay = overlay.unsetUserProperty(o);
        }
      }
      return overlay;
    }


    private ConfigOverlay applyUnset(CommandOperation op, ConfigOverlay overlay) {
      List<String> name = op.getStrs(CommandOperation.ROOT_OBJ);
      if (op.hasError()) return overlay;

      for (String o : name) {
        if (!ConfigOverlay.isEditableProp(o, false, null)) {
          op.addError(formatString(NOT_EDITABLE, name));
        } else {
          overlay = overlay.unsetProperty(o);
        }
      }
      return overlay;
    }

    private ConfigOverlay applySetProp(CommandOperation op, ConfigOverlay overlay) {
      Map<String, Object> m = op.getDataMap();
      if (op.hasError()) return overlay;
      for (Map.Entry<String, Object> e : m.entrySet()) {
        String name = e.getKey();
        Object val = e.getValue();
        Class typ = ConfigOverlay.checkEditable(name, false, null);
        if (typ == null) {
          op.addError(formatString(NOT_EDITABLE, name));
          continue;
        }

        if (val != null) {
          if (typ == String.class) val = val.toString();
          String typeErr = "Property {0} must be of {1} type ";
          if (typ == Boolean.class) {
            try {
              val = Boolean.parseBoolean(val.toString());
            } catch (Exception exp) {
              op.addError(formatString(typeErr, name, typ.getSimpleName()));
              continue;
            }
          } else if (typ == Integer.class) {
            try {
              val = Integer.parseInt(val.toString());
            } catch (Exception exp) {
              op.addError(formatString(typeErr, typ.getSimpleName()));
              continue;
            }

          } else if (typ == Float.class) {
            try {
              val = Float.parseFloat(val.toString());
            } catch (Exception exp) {
              op.addError(formatString(typeErr, typ.getSimpleName()));
              continue;
            }

          }
        }


        overlay = overlay.setProperty(name, val);
      }
      return overlay;
    }

  }

  public static String validateName(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= 'A' && c <= 'Z') ||
          (c >= 'a' && c <= 'z') ||
          (c >= '0' && c <= '9') ||
          c == '_' ||
          c == '-' ||
          c == '.'
          ) continue;
      else {
        return formatString("''{0}'' name should only have chars [a-zA-Z_-.0-9] ", s);
      }
    }
    return null;
  }

  static void setWt(SolrQueryRequest req, String wt) {
    SolrParams params = req.getParams();
    if (params.get(CommonParams.WT) != null) return;//wt is set by user
    Map<String, String> map = new HashMap<>(1);
    map.put(CommonParams.WT, wt);
    map.put("indent", "true");
    req.setParams(SolrParams.wrapDefaults(params, new MapSolrParams(map)));
  }

  @Override
  public SolrRequestHandler getSubHandler(String path) {
    if (subPaths.contains(path)) return this;
    if (path.startsWith("/params/")) return this;
    return null;
  }


  private static Set<String> subPaths = new HashSet<>(Arrays.asList("/overlay", "/params",
      "/query", "/jmx", "/requestDispatcher", "/znodeVersion"));

  static {
    for (SolrConfig.SolrPluginInfo solrPluginInfo : SolrConfig.plugins)
      subPaths.add("/" + solrPluginInfo.getCleanTag());

  }

  //////////////////////// SolrInfoMBeans methods //////////////////////


  @Override
  public String getDescription() {
    return "Edit solrconfig.xml";
  }


  @Override
  public String getVersion() {
    return getClass().getPackage().getSpecificationVersion();
  }

  @Override
  public Category getCategory() {
    return Category.OTHER;
  }


  public static final String SET_PROPERTY = "set-property";
  public static final String UNSET_PROPERTY = "unset-property";
  public static final String SET_USER_PROPERTY = "set-user-property";
  public static final String UNSET_USER_PROPERTY = "unset-user-property";
  public static final String SET = "set";
  public static final String UPDATE = "update";
  public static final String CREATE = "create";
  private static Set<String> cmdPrefixes = ImmutableSet.of(CREATE, UPDATE, "delete", "add");

  /**
   * Block up to a specified maximum time until we see agreement on the schema
   * version in ZooKeeper across all replicas for a collection.
   */
  private static void waitForAllReplicasState(String collection,
                                              ZkController zkController,
                                              String prop,
                                              int expectedVersion,
                                              int maxWaitSecs) {
    long startMs = System.currentTimeMillis();
    // get a list of active replica cores to query for the schema zk version (skipping this core of course)
    List<PerReplicaCallable> concurrentTasks = new ArrayList<>();

    for (String coreUrl : getActiveReplicaCoreUrls(zkController, collection)) {
      PerReplicaCallable e = new PerReplicaCallable(coreUrl, prop, expectedVersion, maxWaitSecs);
      concurrentTasks.add(e);
    }
    if (concurrentTasks.isEmpty()) return; // nothing to wait for ...

    log.info(formatString("Waiting up to {0} secs for {1} replicas to set the property {2} to be of version {3} for collection {4}",
        maxWaitSecs, concurrentTasks.size(), prop, expectedVersion, collection));

    // use an executor service to invoke schema zk version requests in parallel with a max wait time
    int poolSize = Math.min(concurrentTasks.size(), 10);
    ExecutorService parallelExecutor =
        ExecutorUtil.newMDCAwareFixedThreadPool(poolSize, new DefaultSolrThreadFactory("solrHandlerExecutor"));
    try {
      List<Future<Boolean>> results =
          parallelExecutor.invokeAll(concurrentTasks, maxWaitSecs, TimeUnit.SECONDS);

      // determine whether all replicas have the update
      List<String> failedList = null; // lazily init'd
      for (int f = 0; f < results.size(); f++) {
        Boolean success = false;
        Future<Boolean> next = results.get(f);
        if (next.isDone() && !next.isCancelled()) {
          // looks to have finished, but need to check if it succeeded
          try {
            success = next.get();
          } catch (ExecutionException e) {
            // shouldn't happen since we checked isCancelled
          }
        }

        if (!success) {
          String coreUrl = concurrentTasks.get(f).coreUrl;
          log.warn("Core " + coreUrl + "could not get the expected version " + expectedVersion);
          if (failedList == null) failedList = new ArrayList<>();
          failedList.add(coreUrl);
        }
      }

      // if any tasks haven't completed within the specified timeout, it's an error
      if (failedList != null)
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            formatString("{0} out of {1} the property {2} to be of version {3} within {4} seconds! Failed cores: {5}",
                failedList.size(), concurrentTasks.size() + 1, prop, expectedVersion, maxWaitSecs, failedList));

    } catch (InterruptedException ie) {
      log.warn(formatString(
          "Core  was interrupted . trying to set the property {1} to version {2} to propagate to {3} replicas for collection {4}",
          prop, expectedVersion, concurrentTasks.size(), collection));
      Thread.currentThread().interrupt();
    } finally {
      ExecutorUtil.shutdownNowAndAwaitTermination(parallelExecutor);
    }

    long diffMs = (System.currentTimeMillis() - startMs);
    log.info(formatString(
        "Took {0} secs to set the property {1} to be of version {2} for collection {3}",
        Math.round(diffMs / 1000d), prop, expectedVersion, collection));
  }

  public static List<String> getActiveReplicaCoreUrls(ZkController zkController,
                                                      String collection) {
    List<String> activeReplicaCoreUrls = new ArrayList<>();
    ClusterState clusterState = zkController.getZkStateReader().getClusterState();
    Set<String> liveNodes = clusterState.getLiveNodes();
    Collection<Slice> activeSlices = clusterState.getActiveSlices(collection);
    if (activeSlices != null && activeSlices.size() > 0) {
      for (Slice next : activeSlices) {
        Map<String, Replica> replicasMap = next.getReplicasMap();
        if (replicasMap != null) {
          for (Map.Entry<String, Replica> entry : replicasMap.entrySet()) {
            Replica replica = entry.getValue();
            if (replica.getState() == Replica.State.ACTIVE && liveNodes.contains(replica.getNodeName())) {
              activeReplicaCoreUrls.add(replica.getCoreUrl());
            }
          }
        }
      }
    }
    return activeReplicaCoreUrls;
  }

  private static class PerReplicaCallable extends SolrRequest implements Callable<Boolean> {
    String coreUrl;
    String prop;
    int expectedZkVersion;
    Number remoteVersion = null;
    int maxWait;

    PerReplicaCallable(String coreUrl, String prop, int expectedZkVersion, int maxWait) {
      super(METHOD.GET, "/config/" + ZNODEVER);
      this.coreUrl = coreUrl;
      this.expectedZkVersion = expectedZkVersion;
      this.prop = prop;
      this.maxWait = maxWait;
    }

    @Override
    public SolrParams getParams() {
      return new ModifiableSolrParams()
          .set(prop, expectedZkVersion)
          .set(CommonParams.WT, CommonParams.JAVABIN);
    }

    @Override
    public Boolean call() throws Exception {
      long startTime = System.currentTimeMillis();
      int attempts = 0;
      try (HttpSolrClient solr = new HttpSolrClient(coreUrl)) {
        // eventually, this loop will get killed by the ExecutorService's timeout
        while (true) {
          try {
            long timeElapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (timeElapsed >= maxWait) {
              return false;
            }
            log.info("Time elapsed : {} secs, maxWait {}", timeElapsed, maxWait);
            Thread.sleep(100);
            NamedList<Object> resp = solr.httpUriRequest(this).future.get();
            if (resp != null) {
              Map m = (Map) resp.get(ZNODEVER);
              if (m != null) {
                remoteVersion = (Number) m.get(prop);
                if (remoteVersion != null && remoteVersion.intValue() >= expectedZkVersion) break;
              }
            }

            attempts++;
            log.info(formatString("Could not get expectedVersion {0} from {1} for prop {2}   after {3} attempts", expectedZkVersion, coreUrl, prop, attempts));
          } catch (Exception e) {
            if (e instanceof InterruptedException) {
              break; // stop looping
            } else {
              log.warn("Failed to get /schema/zkversion from " + coreUrl + " due to: " + e);
            }
          }
        }
      }
      return true;
    }

    @Override
    public Collection<ContentStream> getContentStreams() throws IOException {
      return null;
    }

    @Override
    protected SolrResponse createResponse(SolrClient client) {
      return null;
    }
  }
}
