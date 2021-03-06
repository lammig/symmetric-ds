/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.service.impl;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.sql.ISqlRowMapper;
import org.jumpmind.db.sql.ISqlTransaction;
import org.jumpmind.db.sql.Row;
import org.jumpmind.db.sql.mapper.LongMapper;
import org.jumpmind.db.sql.mapper.StringMapper;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.db.ISymmetricDialect;
import org.jumpmind.symmetric.ext.IOutgoingBatchFilter;
import org.jumpmind.symmetric.model.Channel;
import org.jumpmind.symmetric.model.LoadSummary;
import org.jumpmind.symmetric.model.NodeChannel;
import org.jumpmind.symmetric.model.NodeGroupChannelWindow;
import org.jumpmind.symmetric.model.NodeHost;
import org.jumpmind.symmetric.model.NodeSecurity;
import org.jumpmind.symmetric.model.OutgoingBatch;
import org.jumpmind.symmetric.model.OutgoingBatch.Status;
import org.jumpmind.symmetric.model.OutgoingBatchSummary;
import org.jumpmind.symmetric.model.OutgoingBatches;
import org.jumpmind.symmetric.model.OutgoingLoadSummary;
import org.jumpmind.symmetric.service.IClusterService;
import org.jumpmind.symmetric.service.IConfigurationService;
import org.jumpmind.symmetric.service.IExtensionService;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.symmetric.service.IOutgoingBatchService;
import org.jumpmind.symmetric.service.IParameterService;
import org.jumpmind.symmetric.service.ISequenceService;
import org.jumpmind.util.AppUtils;
import org.jumpmind.util.FormatUtils;

/**
 * @see IOutgoingBatchService
 */
public class OutgoingBatchService extends AbstractService implements IOutgoingBatchService {

    private INodeService nodeService;

    private IConfigurationService configurationService;

    private ISequenceService sequenceService;

    private IClusterService clusterService;
    
    private IExtensionService extensionService;

    public OutgoingBatchService(IParameterService parameterService,
            ISymmetricDialect symmetricDialect, INodeService nodeService,
            IConfigurationService configurationService, ISequenceService sequenceService,
            IClusterService clusterService, IExtensionService extensionService) {
        super(parameterService, symmetricDialect);
        this.nodeService = nodeService;
        this.configurationService = configurationService;
        this.sequenceService = sequenceService;
        this.clusterService = clusterService;
        this.extensionService = extensionService;
        setSqlMap(new OutgoingBatchServiceSqlMap(symmetricDialect.getPlatform(),
                createSqlReplacementTokens()));
    }
    
    @Override
    public int cancelLoadBatches(long loadId) {
        return sqlTemplate.update(getSql("cancelLoadBatchesSql"), loadId);
    }

    public void markAllAsSentForNode(String nodeId, boolean includeConfigChannel) {
        OutgoingBatches batches = null;
        int configCount;
        do {
            configCount = 0;
            batches = getOutgoingBatches(nodeId, true);
            List<OutgoingBatch> list = batches.getBatches();
            /*
             * Sort in reverse order so we don't get fk errors for batches that
             * are currently processing. We don't make the update transactional
             * to prevent contention in highly loaded systems
             */
            Collections.sort(list, new Comparator<OutgoingBatch>() {
                public int compare(OutgoingBatch o1, OutgoingBatch o2) {
                    return -new Long(o1.getBatchId()).compareTo(o2.getBatchId());
                }
            });
                        
            for (OutgoingBatch outgoingBatch : list) {
                if (includeConfigChannel || !outgoingBatch.getChannelId().equals(Constants.CHANNEL_CONFIG)) {
                    outgoingBatch.setStatus(Status.OK);
                    outgoingBatch.setErrorFlag(false);
                    updateOutgoingBatch(outgoingBatch);
                } else {
                    configCount++;
                }
            }
        } while (batches.getBatches().size() > configCount);
    }
    
    public void markAllConfigAsSentForNode(String nodeId) {
        int updateCount;
        do {
            updateCount = 0;
            OutgoingBatches batches = getOutgoingBatches(nodeId, false);
            List<OutgoingBatch> list = batches.getBatches();                       
            for (OutgoingBatch outgoingBatch : list) {
                if (outgoingBatch.getChannelId().equals(Constants.CHANNEL_CONFIG)) {
                    outgoingBatch.setStatus(Status.OK);
                    outgoingBatch.setErrorFlag(false);
                    outgoingBatch.setIgnoreCount(1);
                    updateOutgoingBatch(outgoingBatch);
                    updateCount++;
                }
            }
        } while (updateCount > 0);
    }

    @Override
    public void markAllChannelAsSent(String channelId) {
        markAllChannelAsSent(channelId, null);
    }
    
    @Override
    public void markAllChannelAsSent(String channelId, String tableName) {
        String sql = getSql("cancelChannelBatchesSql");
        if (!StringUtils.isEmpty(tableName)) {
            sql += getSql("cancelChannelBatchesTableSql");
        }
        sqlTemplate.update(sql, channelId, tableName);
    }

    public void copyOutgoingBatches(String channelId, long startBatchId, String fromNodeId, String toNodeId) {
        log.info("Copying outgoing batches for channel '{}' from node '{}' to node '{}' starting at {}", new Object[] {channelId, fromNodeId, toNodeId, startBatchId});
        sqlTemplate.update(getSql("deleteOutgoingBatchesForNodeSql"), toNodeId, channelId, fromNodeId, channelId);
        int count = sqlTemplate.update(getSql("copyOutgoingBatchesSql"), toNodeId, fromNodeId, channelId, startBatchId);
        log.info("Copied {} outgoing batches for channel '{}' from node '{}' to node '{}'", new Object[] {count, channelId, fromNodeId, toNodeId});
    }

    public void updateAbandonedRoutingBatches() {
        int count = sqlTemplate.queryForInt(getSql("countOutgoingBatchesWithStatusSql"), Status.RT.name());
        if (count > 0) {
            log.info("Cleaning up {} batches that were abandoned by a failed or aborted attempt at routing", count);
            sqlTemplate.update(getSql("updateOutgoingBatchesStatusSql"), Status.OK.name(),
                    Status.RT.name());
        }
    }

    public void updateOutgoingBatches(List<OutgoingBatch> outgoingBatches) {
        for (OutgoingBatch batch : outgoingBatches) {
            updateOutgoingBatch(batch);
        }
    }

    public void updateOutgoingBatch(OutgoingBatch outgoingBatch) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            updateOutgoingBatch(transaction, outgoingBatch);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    public void updateOutgoingBatch(ISqlTransaction transaction, OutgoingBatch outgoingBatch) {
        outgoingBatch.setLastUpdatedTime(new Date());
        outgoingBatch.setLastUpdatedHostName(clusterService.getServerId());
        transaction.prepareAndExecute(
                getSql("updateOutgoingBatchSql"),
                new Object[] { outgoingBatch.getStatus().name(), outgoingBatch.getLoadId(),
                       outgoingBatch.isExtractJobFlag() ? 1: 0,
                        outgoingBatch.isLoadFlag() ? 1 : 0, outgoingBatch.isErrorFlag() ? 1 : 0,
                        outgoingBatch.getByteCount(), outgoingBatch.getExtractCount(),
                        outgoingBatch.getSentCount(), outgoingBatch.getLoadCount(),
                        outgoingBatch.getDataEventCount(), outgoingBatch.getReloadEventCount(),
                        outgoingBatch.getInsertEventCount(), outgoingBatch.getUpdateEventCount(),
                        outgoingBatch.getDeleteEventCount(), outgoingBatch.getOtherEventCount(),
                        outgoingBatch.getIgnoreCount(), outgoingBatch.getRouterMillis(),
                        outgoingBatch.getNetworkMillis(), outgoingBatch.getFilterMillis(),
                        outgoingBatch.getLoadMillis(), outgoingBatch.getExtractMillis(),
                        outgoingBatch.getSqlState(), outgoingBatch.getSqlCode(),
                        FormatUtils.abbreviateForLogging(outgoingBatch.getSqlMessage()),
                        outgoingBatch.getFailedDataId(), outgoingBatch.getLastUpdatedHostName(),
                        outgoingBatch.getLastUpdatedTime(), outgoingBatch.getSummary(), 
                        outgoingBatch.getBatchId(), outgoingBatch.getNodeId() }, new int[] { Types.CHAR, Types.BIGINT,
                        Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.BIGINT, Types.BIGINT, Types.BIGINT,
                        Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.BIGINT,
                        Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.BIGINT,
                        Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.NUMERIC,
                        Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.TIMESTAMP, Types.VARCHAR, 
                        symmetricDialect.getSqlTypeForIds(), Types.VARCHAR });
    }

    public void insertOutgoingBatch(final OutgoingBatch outgoingBatch) {
        ISqlTransaction transaction = null;
        try {
            transaction = sqlTemplate.startSqlTransaction();
            insertOutgoingBatch(transaction, outgoingBatch);
            transaction.commit();
        } catch (Error ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw ex;
        } finally {
            close(transaction);
        }
    }

    public void insertOutgoingBatch(ISqlTransaction transaction, OutgoingBatch outgoingBatch) {
        outgoingBatch.setLastUpdatedHostName(clusterService.getServerId());

        long batchId = outgoingBatch.getBatchId();
        if (batchId <= 0) {
            batchId = sequenceService.nextVal(transaction, Constants.SEQUENCE_OUTGOING_BATCH);
        }
        transaction.prepareAndExecute(getSql("insertOutgoingBatchSql"), batchId, outgoingBatch
                .getNodeId(), outgoingBatch.getChannelId(), outgoingBatch.getStatus().name(),
                outgoingBatch.getLoadId(), outgoingBatch.isExtractJobFlag() ? 1: 0, outgoingBatch.isLoadFlag() ? 1 : 0, outgoingBatch
                        .isCommonFlag() ? 1 : 0, outgoingBatch.getReloadEventCount(), outgoingBatch
                        .getOtherEventCount(), outgoingBatch.getLastUpdatedHostName(),
                outgoingBatch.getCreateBy(), outgoingBatch.getSummary());
        outgoingBatch.setBatchId(batchId);
    }

    public OutgoingBatch findOutgoingBatch(long batchId, String nodeId) {
        List<OutgoingBatch> list = null;
        if (StringUtils.isNotBlank(nodeId)) {
            list = (List<OutgoingBatch>) sqlTemplateDirty.query(
                    getSql("selectOutgoingBatchPrefixSql", "findOutgoingBatchSql"),
                    new OutgoingBatchMapper(true), new Object[] { batchId, nodeId },
                    new int[] { symmetricDialect.getSqlTypeForIds(), Types.VARCHAR });
        } else {
            /*
             * Pushing to an older version of symmetric might result in a batch
             * without the node id
             */
            list = (List<OutgoingBatch>) sqlTemplateDirty.query(
                    getSql("selectOutgoingBatchPrefixSql", "findOutgoingBatchByIdOnlySql"),
                    new OutgoingBatchMapper(true), new Object[] { batchId },
                    new int[] { symmetricDialect.getSqlTypeForIds() });
        }
        if (list != null && list.size() > 0) {
            return list.get(0);
        } else {
            return null;
        }
    }

    public int countOutgoingBatchesInError() {
        return sqlTemplateDirty.queryForInt(getSql("countOutgoingBatchesErrorsSql"));
    }

    public int countOutgoingBatchesInError(String channelId) {
        return sqlTemplateDirty.queryForInt(getSql("countOutgoingBatchesErrorsOnChannelSql"), channelId);
    }

    @Override
    public int countOutgoingBatchesUnsent() {
        return sqlTemplateDirty.queryForInt(getSql("countOutgoingBatchesUnsentSql"));
    }

    @Override
    public int countOutgoingBatchesUnsent(String channelId) {
        return sqlTemplateDirty.queryForInt(getSql("countOutgoingBatchesUnsentOnChannelSql"), channelId);
    }
    
    @Override
    public Map<String, Integer> countOutgoingBatchesPendingByChannel(String nodeId) {                
        List<Row> rows = sqlTemplateDirty.query(getSql("countOutgoingBatchesByChannelSql"), new Object[]{nodeId});
        Map<String, Integer> results = new HashMap<String, Integer>();
        if (rows != null && ! rows.isEmpty()) {            
            for (Row row : rows) {
                results.put(row.getString("channel_id"), row.getInt("batch_count"));
            }
        }
        
        Set<String> channelIds = configurationService.getChannels(false).keySet();
        for (String channelId : channelIds) {
            if (!results.containsKey(channelId) && !Constants.CHANNEL_HEARTBEAT.equals(channelId)) {
                results.put(channelId, 0);
            }
        }
        
        return results;
    }

    @Override
    public int countOutgoingBatches(List<String> nodeIds, List<String> channels,
            List<OutgoingBatch.Status> statuses, List<String> loads) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("NODES", nodeIds);
        params.put("CHANNELS", channels);
        params.put("STATUSES", toStringList(statuses));

        return sqlTemplateDirty
                .queryForInt(
                        getSql("selectCountBatchesPrefixSql",
                                buildBatchWhere(nodeIds, channels, statuses, loads)), params);
    }

    public List<OutgoingBatch> listOutgoingBatches(List<String> nodeIds, List<String> channels,
            List<OutgoingBatch.Status> statuses, List<String> loads, long startAtBatchId, final int maxRowsToRetrieve,
            boolean ascending) {

        String where = buildBatchWhere(nodeIds, channels, statuses, loads);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("NODES", nodeIds);
        params.put("CHANNELS", channels);
        params.put("STATUSES", toStringList(statuses));
        params.put("LOADS", loads);
        String startAtBatchIdSql = null;
        if (startAtBatchId > 0) {
            if (StringUtils.isBlank(where)) {
                where = " where 1=1 ";
            }
            params.put("BATCH_ID", startAtBatchId);
            startAtBatchIdSql = " and batch_id = :BATCH_ID ";
        }

        String sql = getSql("selectOutgoingBatchPrefixSql", where, startAtBatchIdSql,
                ascending ? " order by batch_id asc" : " order by batch_id desc");
        return sqlTemplateDirty.query(sql, maxRowsToRetrieve, new OutgoingBatchMapper(true),
                params);

    }

    protected List<String> toStringList(List<OutgoingBatch.Status> statuses) {
        List<String> statusStrings = new ArrayList<String>(statuses.size());
        for (Status status : statuses) {
            statusStrings.add(status.name());
        }
        return statusStrings;

    }

    protected boolean containsOnlyStatus(OutgoingBatch.Status status,
            List<OutgoingBatch.Status> statuses) {
        return statuses.size() == 1 && statuses.get(0) == status;
    }

    /**
     * Select batches to process. Batches that are NOT in error will be returned
     * first. They will be ordered by batch id as the batches will have already
     * been created by {@link #buildOutgoingBatches(String)} in channel priority
     * order.
     */
    public OutgoingBatches getOutgoingBatches(String nodeId, boolean includeDisabledChannels) {
    	return getOutgoingBatches(nodeId, null, includeDisabledChannels);
    }

    @Override
    public OutgoingBatches getOutgoingBatches(String nodeId, String channelThread, boolean includeDisabledChannels) {
        long ts = System.currentTimeMillis();
        final int maxNumberOfBatchesToSelect = parameterService.getInt(
                ParameterConstants.OUTGOING_BATCH_MAX_BATCHES_TO_SELECT, 1000);
        
        String sql = null;
        Object[] params = null;
        int[] types = null;
        
        if (channelThread != null) {
        	sql = getSql("selectOutgoingBatchPrefixSql", "selectOutgoingBatchChannelSql");
        	params = new Object[] { nodeId, channelThread, OutgoingBatch.Status.RQ.name(), OutgoingBatch.Status.NE.name(),
                    OutgoingBatch.Status.QY.name(), OutgoingBatch.Status.SE.name(),
                    OutgoingBatch.Status.LD.name(), OutgoingBatch.Status.ER.name(),
                    OutgoingBatch.Status.IG.name(), OutgoingBatch.Status.RS.name()};
        	types = new int[] {
        	        Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.CHAR, Types.CHAR, Types.CHAR,
        	        Types.CHAR, Types.CHAR, Types.CHAR, Types.CHAR
        	};
        }
        else {
        	sql = getSql("selectOutgoingBatchPrefixSql", "selectOutgoingBatchSql");
        	params = new Object[] { nodeId, OutgoingBatch.Status.RQ.name(), OutgoingBatch.Status.NE.name(),
                    OutgoingBatch.Status.QY.name(), OutgoingBatch.Status.SE.name(),
                    OutgoingBatch.Status.LD.name(), OutgoingBatch.Status.ER.name(),
                    OutgoingBatch.Status.IG.name(), OutgoingBatch.Status.RS.name()};
            types = new int[] {
                    Types.VARCHAR, Types.CHAR, Types.CHAR, Types.CHAR, Types.CHAR,
                    Types.CHAR, Types.CHAR, Types.CHAR, Types.CHAR
            };

        }
        
        List<OutgoingBatch> list = (List<OutgoingBatch>) sqlTemplate.query(
                sql, maxNumberOfBatchesToSelect, new OutgoingBatchMapper(includeDisabledChannels), params, types);
        
        OutgoingBatches batches = new OutgoingBatches(list);

        List<NodeChannel> channels = new ArrayList<NodeChannel>(configurationService.getNodeChannels(nodeId, true));
        batches.sortChannels(channels);

        List<IOutgoingBatchFilter> filters = extensionService.getExtensionPointList(IOutgoingBatchFilter.class);

        List<OutgoingBatch> keepers = new ArrayList<OutgoingBatch>();

        for (NodeChannel channel : channels) {
            List<OutgoingBatch> batchesForChannel = getBatchesForChannelWindows(
                    batches,
                    nodeId,
                    channel,
                    configurationService.getNodeGroupChannelWindows(
                            parameterService.getNodeGroupId(), channel.getChannelId()));
            if (filters != null) {
                for (IOutgoingBatchFilter filter : filters) {
                    batchesForChannel = filter.filter(channel, batchesForChannel);
                }
            }
            if (parameterService.is(ParameterConstants.DATA_EXTRACTOR_ENABLED)
                    || channel.getChannelId().equals(Constants.CHANNEL_CONFIG)) {
                keepers.addAll(batchesForChannel);
            }
        }
        batches.setBatches(keepers);

        long executeTimeInMs = System.currentTimeMillis() - ts;
        if (executeTimeInMs > Constants.LONG_OPERATION_THRESHOLD) {
            log.info("Selecting {} outgoing batch rows for node {} on queue '{}' took {} ms", list.size(), nodeId, channelThread, executeTimeInMs);
        }

        return batches;
    }
    
    public List<OutgoingBatch> getBatchesForChannelWindows(OutgoingBatches batches,
            String targetNodeId, NodeChannel channel, List<NodeGroupChannelWindow> windows) {
        List<OutgoingBatch> keeping = new ArrayList<OutgoingBatch>();
        List<OutgoingBatch> current = batches.getBatches();
        if (current != null && current.size() > 0) {
            if (inTimeWindow(windows, targetNodeId)) {
                int maxBatchesToSend = channel.getMaxBatchToSend();
                for (OutgoingBatch outgoingBatch : current) {
                    if (channel.getChannelId().equals(outgoingBatch.getChannelId())
                            && maxBatchesToSend > 0) {
                        keeping.add(outgoingBatch);
                        maxBatchesToSend--;
                    }
                }
            }
        }
        return keeping;
    }

    /**
     * If {@link NodeGroupChannelWindow}s are defined for this channel, then
     * check to see if the time (according to the offset passed in) is within on
     * of the configured windows.
     */
    public boolean inTimeWindow(List<NodeGroupChannelWindow> windows, String targetNodeId) {
        if (windows != null && windows.size() > 0) {
            for (NodeGroupChannelWindow window : windows) {
                String timezoneOffset = null;
                List<NodeHost> hosts = nodeService.findNodeHosts(targetNodeId);
                if (hosts.size() > 0) {
                    timezoneOffset = hosts.get(0).getTimezoneOffset();
                } else {
                    timezoneOffset = AppUtils.getTimezoneOffset();
                }
                if (window.inTimeWindow(timezoneOffset)) {
                    return true;
                }
            }
            return false;
        } else {
            return true;
        }

    }

    public OutgoingBatches getOutgoingBatchRange(String nodeId, Date startDate, Date endDate, String... channels) {
        OutgoingBatches batches = new OutgoingBatches();
        List<OutgoingBatch> batchList = new ArrayList<OutgoingBatch>();
        for (String channel : channels) {
            batchList.addAll(sqlTemplate.query(
                    getSql("selectOutgoingBatchPrefixSql", "selectOutgoingBatchTimeRangeSql"),
                    new OutgoingBatchMapper(true), nodeId, channel, startDate, endDate));
        }
        batches.setBatches(batchList);
        return batches;
    }

    public OutgoingBatches getOutgoingBatchRange(long startBatchId, long endBatchId) {
        OutgoingBatches batches = new OutgoingBatches();
        batches.setBatches(sqlTemplate.query(
                getSql("selectOutgoingBatchPrefixSql", "selectOutgoingBatchRangeSql"),
                new OutgoingBatchMapper(true), startBatchId,
                endBatchId));
        return batches;
    }

    public OutgoingBatches getOutgoingBatchByLoad(long loadId) {
        OutgoingBatches batches = new OutgoingBatches();
        batches.setBatches(sqlTemplate.query(
                getSql("selectOutgoingBatchPrefixSql", "selectOutgoingBatchLoadSql"),
                new OutgoingBatchMapper(true), loadId));
        return batches;
    }
    
    public OutgoingBatches getOutgoingBatchErrors(int maxRows) {
        OutgoingBatches batches = new OutgoingBatches();
        batches.setBatches(sqlTemplateDirty.query(
                getSql("selectOutgoingBatchPrefixSql", "selectOutgoingBatchErrorsSql"), maxRows,
                new OutgoingBatchMapper(true), null, null));
        return batches;
    }
    
    public List<String> getNodesInError() {
        return sqlTemplate.query(getSql("selectNodesInErrorSql"), new StringMapper());
    }
    
    public List<OutgoingBatch> getNextOutgoingBatchForEachNode() {
        return sqlTemplate.query(
                getSql("getNextOutgoingBatchForEachNodeSql"),
                new OutgoingBatchMapper(true, true));
    }

    public boolean isInitialLoadComplete(String nodeId) {
        return areAllLoadBatchesComplete(nodeId)
                && !isUnsentDataOnChannelForNode(Constants.CHANNEL_CONFIG, nodeId);
    }

    public boolean areAllLoadBatchesComplete(String nodeId) {

        NodeSecurity security = nodeService.findNodeSecurity(nodeId);
        if (security == null || security.isInitialLoadEnabled()) {
            return false;
        }

        List<String> statuses = (List<String>) sqlTemplate.query(getSql("initialLoadStatusSql"),
                new StringMapper(), nodeId, 1);
        if (statuses == null || statuses.size() == 0) {
            throw new RuntimeException("The initial load has not been started for " + nodeId);
        }

        for (String status : statuses) {
            if (!Status.OK.name().equals(status)) {
                return false;
            }
        }
        return true;
    }

    public boolean isUnsentDataOnChannelForNode(String channelId, String nodeId) {
        int unsentCount = sqlTemplate.queryForInt(getSql("unsentBatchesForNodeIdChannelIdSql"),
                new Object[] { nodeId, channelId });
        if (unsentCount > 0) {
            return true;
        }

        // Do we need to check for unbatched data?
        return false;
    }

    public List<OutgoingBatchSummary> findOutgoingBatchSummary(Status... statuses) {
        Object[] args = new Object[statuses.length];
        StringBuilder inList = new StringBuilder();
        for (int i = 0; i < statuses.length; i++) {
            args[i] = statuses[i].name();
            inList.append("?,");
        }

        String sql = getSql("selectOutgoingBatchSummaryByStatusSql").replace(":STATUS_LIST",
                inList.substring(0, inList.length() - 1));

        return sqlTemplateDirty.query(sql, new OutgoingBatchSummaryMapper(), args);
    }

    public Set<Long> getActiveLoads(String sourceNodeId) {
        Set<Long> loads = new HashSet<Long>();
        
        List<Long> inProcess = sqlTemplateDirty.query(getSql("getActiveLoadsSql"), new ISqlRowMapper<Long>() {
            @Override
            public Long mapRow(Row rs) {
                return rs.getLong("load_id");
            }
        }, sourceNodeId);
        loads.addAll(inProcess);
        
        return loads;
    }
    
    public List<String> getQueuedLoads(String sourceNodeId) {
    	return sqlTemplateDirty.query(getSql("getUnprocessedReloadRequestsSql"), new ISqlRowMapper<String>() {
            @Override
            public String mapRow(Row rs) {
                return rs.getString("source_node_id") + " to " + rs.getString("target_node_id");
            }
        }, sourceNodeId);
    }
    
    public LoadSummary getLoadSummary(long loadId) {
        return sqlTemplateDirty.queryForObject(getSql("getLoadSummarySql"),  
                new ISqlRowMapper<LoadSummary>() {
            
            public LoadSummary mapRow(Row rs) {
                LoadSummary summary = new LoadSummary();
                summary.setLoadId(rs.getLong("load_id"));
                summary.setNodeId(rs.getString("target_node_id"));
                summary.setCreateBy(rs.getString("last_update_by"));
                summary.setTableCount(rs.getInt("table_count"));
                String triggerId = rs.getString("trigger_id");
                if (triggerId != null && triggerId.equals(ParameterConstants.ALL)) {
                    summary.setFullLoad(true);
                } else {
                    summary.setFullLoad(false);
                }
                summary.setCreateFirst(rs.getBoolean("create_table"));
                summary.setDeleteFirst(rs.getBoolean("delete_first"));
                summary.setRequestProcessed(rs.getBoolean("processed"));
                summary.setConditional(rs.getBoolean("reload_select"));
                summary.setCustomSql(rs.getBoolean("before_custom_sql"));
                return summary;
            }           
        }, loadId);
    }

    public Map<String, Map<String, LoadStatusSummary>> getLoadStatusSummarySql(long loadId) {
        LoadStatusByQueueMapper mapper = new LoadStatusByQueueMapper();        
        sqlTemplateDirty.query(getSql("getLoadStatusSummarySql"), mapper, loadId);
        return mapper.getResults();
    }
    
    private class LoadStatusByQueueMapper implements ISqlRowMapper<Object> {
        Map<String, Map<String, LoadStatusSummary>> results = new HashMap<String, Map<String, LoadStatusSummary>>();
        
        @Override
        public Object mapRow(Row rs) {
            String queue = rs.getString("queue");
            String status = rs.getString("status");
            
            Map<String, LoadStatusSummary> statusMap = results.get(queue);
            if (statusMap == null) {
            	statusMap = new HashMap<String, LoadStatusSummary>();
            }
            
            LoadStatusSummary statusSummary = new LoadStatusSummary();
        	statusSummary.setCreateTime(rs.getDateTime("create_time"));
        	statusSummary.setLastUpdateTime(rs.getDateTime("last_update_time"));
        	statusSummary.setByteCount(rs.getLong("byte_count"));
        	statusSummary.setDataEventCount(rs.getLong("data_events"));
        	statusSummary.setCount(rs.getInt("count"));
            
        	statusMap.put(status, statusSummary);
            results.put(queue, statusMap);
            
            return null;
        }
        
        public Map<String, Map<String, LoadStatusSummary>> getResults() {
            return results;
        }
    }
    
    public class LoadStatusSummary {
    	private Date createTime;
    	private Date lastUpdateTime;
    	private long dataEventCount;
    	private long byteCount;
    	private String status;
    	private int count;
    	
    	public String getStatus() {
			return status;
		}
		public void setStatus(String status) {
			this.status = status;
		}
		public int getCount() {
			return count;
		}
		public void setCount(int count) {
			this.count = count;
		}
		public Date getCreateTime() {
			return createTime;
		}
		public void setCreateTime(Date createTime) {
			this.createTime = createTime;
		}
		public Date getLastUpdateTime() {
			return lastUpdateTime;
		}
		public void setLastUpdateTime(Date lastUpdateTime) {
			this.lastUpdateTime = lastUpdateTime;
		}
		public long getDataEventCount() {
			return dataEventCount;
		}
		public void setDataEventCount(long dataEventCount) {
			this.dataEventCount = dataEventCount;
		}
		public long getByteCount() {
			return byteCount;
		}
		public void setByteCount(long byteCount) {
			this.byteCount = byteCount;
		}
    	
    	
    }
    
    public List<OutgoingLoadSummary> getLoadSummaries(boolean activeOnly) {
        final Map<String, OutgoingLoadSummary> loadSummaries = new TreeMap<String, OutgoingLoadSummary>();
        sqlTemplateDirty.query(getSql("getLoadSummariesSql"), new ISqlRowMapper<OutgoingLoadSummary>() {
            public OutgoingLoadSummary mapRow(Row rs) {
                long loadId = rs.getLong("load_id");
                String nodeId = rs.getString("node_id");
                String loadNodeId = String.format("%010d-%s", loadId, nodeId);
                OutgoingLoadSummary summary = loadSummaries.get(loadNodeId);
                if (summary == null) {
                    summary = new OutgoingLoadSummary();
                    summary.setLoadId(loadId);
                    summary.setNodeId(nodeId);
                    summary.setChannelId(rs.getString("channel_id"));
                    summary.setCreateBy(rs.getString("create_by"));
                    loadSummaries.put(loadNodeId, summary);
                }

                Status status = Status.valueOf(rs.getString("status"));
                int count = rs.getInt("cnt");

                Date lastUpdateTime = rs.getDateTime("last_update_time");
                if (summary.getLastUpdateTime() == null
                        || summary.getLastUpdateTime().before(lastUpdateTime)) {
                    summary.setLastUpdateTime(lastUpdateTime);
                }

                Date createTime = rs.getDateTime("create_time");
                if (summary.getCreateTime() == null || summary.getCreateTime().after(createTime)) {
                    summary.setCreateTime(createTime);
                }

                summary.setReloadBatchCount(summary.getReloadBatchCount() + count);

                if (status == Status.OK || status == Status.IG) {
                    summary.setFinishedBatchCount(summary.getFinishedBatchCount() + count);
                } else {
                    summary.setPendingBatchCount(summary.getPendingBatchCount() + count);

                    boolean inError = rs.getBoolean("error_flag");
                    summary.setInError(inError || summary.isInError());

                    if (status != Status.NE && count == 1) {
                        summary.setCurrentBatchId(rs.getLong("current_batch_id"));
                        summary.setCurrentDataEventCount(rs.getLong("current_data_event_count"));
                    }

                }
                return null;
            }
        });

        List<OutgoingLoadSummary> loads = new ArrayList<OutgoingLoadSummary>(loadSummaries.values());
        Iterator<OutgoingLoadSummary> it = loads.iterator();
        while (it.hasNext()) {
            OutgoingLoadSummary loadSummary = it.next();
            if (activeOnly && !loadSummary.isActive()) {
                it.remove();
            }
        }

        return loads;
    }
    
    @Override
    public List<Long> getAllBatches() {
        return sqlTemplateDirty.query(getSql("getAllBatchesSql"), new LongMapper());
    }
    
    class OutgoingBatchSummaryMapper implements ISqlRowMapper<OutgoingBatchSummary> {
        public OutgoingBatchSummary mapRow(Row rs) {
            OutgoingBatchSummary summary = new OutgoingBatchSummary();
            summary.setBatchCount(rs.getInt("batches"));
            summary.setDataCount(rs.getInt("data"));
            summary.setStatus(Status.valueOf(rs.getString("status")));
            summary.setNodeId(rs.getString("node_id"));
            summary.setOldestBatchCreateTime(rs.getDateTime("oldest_batch_time"));
            return summary;
        }
    }

    class OutgoingBatchMapper implements ISqlRowMapper<OutgoingBatch> {

        private boolean statusOnly = false;
        private boolean includeDisabledChannels = false;
        private Map<String, Channel> channels;

        public OutgoingBatchMapper(boolean includeDisabledChannels, boolean statusOnly) {
            this.includeDisabledChannels = includeDisabledChannels;
            this.statusOnly = statusOnly;
            this.channels = configurationService.getChannels(false);
        }

        public OutgoingBatchMapper(boolean includeDisabledChannels) {
            this(includeDisabledChannels, false);
        }

        public OutgoingBatch mapRow(Row rs) {
            String channelId = rs.getString("channel_id");
            Channel channel = channels.get(channelId);
            if (channel != null && (includeDisabledChannels || channel.isEnabled())) {
                OutgoingBatch batch = new OutgoingBatch();
                batch.setNodeId(rs.getString("node_id"));
                batch.setStatus(rs.getString("status"));
                batch.setBatchId(rs.getLong("batch_id"));
                if (!statusOnly) {
                    batch.setChannelId(channelId);
                    batch.setByteCount(rs.getLong("byte_count"));
                    batch.setExtractCount(rs.getLong("extract_count"));
                    batch.setSentCount(rs.getLong("sent_count"));
                    batch.setLoadCount(rs.getLong("load_count"));
                    batch.setDataEventCount(rs.getLong("data_event_count"));
                    batch.setReloadEventCount(rs.getLong("reload_event_count"));
                    batch.setInsertEventCount(rs.getLong("insert_event_count"));
                    batch.setUpdateEventCount(rs.getLong("update_event_count"));
                    batch.setDeleteEventCount(rs.getLong("delete_event_count"));
                    batch.setOtherEventCount(rs.getLong("other_event_count"));
                    batch.setIgnoreCount(rs.getLong("ignore_count"));
                    batch.setRouterMillis(rs.getLong("router_millis"));
                    batch.setNetworkMillis(rs.getLong("network_millis"));
                    batch.setFilterMillis(rs.getLong("filter_millis"));
                    batch.setLoadMillis(rs.getLong("load_millis"));
                    batch.setExtractMillis(rs.getLong("extract_millis"));
                    batch.setSqlState(rs.getString("sql_state"));
                    batch.setSqlCode(rs.getInt("sql_code"));
                    batch.setSqlMessage(rs.getString("sql_message"));
                    batch.setFailedDataId(rs.getLong("failed_data_id"));
                    batch.setLastUpdatedHostName(rs.getString("last_update_hostname"));
                    batch.setLastUpdatedTime(rs.getDateTime("last_update_time"));
                    batch.setCreateTime(rs.getDateTime("create_time"));
                    batch.setLoadFlag(rs.getBoolean("load_flag"));
                    batch.setErrorFlag(rs.getBoolean("error_flag"));
                    batch.setCommonFlag(rs.getBoolean("common_flag"));
                    batch.setExtractJobFlag(rs.getBoolean("extract_job_flag"));
                    batch.setLoadId(rs.getLong("load_id"));
                    batch.setCreateBy(rs.getString("create_by"));
                    batch.setSummary(rs.getString("summary"));
                }
                return batch;
            } else {
                return null;
            }
        }
    }
        

}
