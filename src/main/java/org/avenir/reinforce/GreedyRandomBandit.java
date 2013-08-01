/*
 * avenir: Predictive analytic based on Hadoop Map Reduce
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.avenir.reinforce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.DynamicBean;
import org.chombo.util.Utility;

/**
 * Implements greedy multiarm bandit  reinforcement learning algorithms
 * @author pranab
 *
 */
public class GreedyRandomBandit   extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Greedy random bandit problem";
        job.setJobName(jobName);
        
        job.setJarByClass(GreedyRandomBandit.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "avenir");
        job.setMapperClass(GreedyRandomBandit.BanditMapper.class);
        
        job.setMapOutputKeyClass(NullWritable.class);
        job.setMapOutputValueClass(Text.class);

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class BanditMapper extends Mapper<LongWritable, Text, NullWritable, Text> {
		private String fieldDelimRegex;
    	private String fieldDelim ;
		private String[] items;
		private Text outVal  = new Text();
		private int roundNum;
		private float randomSelectionProb;
		private String  probRedAlgorithm;
		private String curGroupID = null;
		private String groupID;
		private int countOrdinal;
		private int rewardOrdinal;
		private static final String PROB_RED_LINEAR = "linear";
		private static final String PROB_RED_LOG_LINEAR = "logLinear";
		private static final String DET_UBC1 = "UBC1";
		private List<DynamicBean> groupItems = new ArrayList<DynamicBean>();
		private static final String ITEM_ID = "itemID";
		private static final String ITEM_COUNT = "count";
		private static final String ITEM_REWARD = "reward";
		private Map<String, Integer> groupBatchCount = new HashMap<String, Integer>();
		
		
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
		 */
		protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration conf = context.getConfiguration();
        	fieldDelimRegex = conf.get("field.delim.regex", ",");
        	fieldDelim = conf.get("field.delim", ",");

        	roundNum = conf.getInt("current.round.num",  2);
        	randomSelectionProb = conf.getFloat("random.selection.prob", (float)0.5);
        	probRedAlgorithm = conf.get("prob.reduction.algorithm", PROB_RED_LINEAR );
        	countOrdinal = conf.getInt("count.ordinal",  -1);
        	rewardOrdinal = conf.getInt("reward.ordinal",  -1);
 
        	//batch size
        	List<String[]> lines = Utility.parseFileLines(conf,  "group.item.count.path",  ",");
        	String groupID;
 			int batchSize;
        	for (String[] line : lines) {
        		groupID= line[0];
        		batchSize = Integer.parseInt(line[1]);
        		groupBatchCount.put(groupID,   batchSize );
        	}
        	
        }

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#cleanup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void cleanup(Context context)  throws IOException, InterruptedException {
			select( context);
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
            groupID = items[0];
    		if (null == curGroupID || !groupID.equals(curGroupID)) {
    			//new group
    			if (null == curGroupID) {
    				collectGroupItems();
    			} else  {
    				select( context);
    			}
    			
    			groupItems.clear();
    			curGroupID = groupID;
    		} else {
    			//existing group
				collectGroupItems();
    		}
        }

        /**
         * @return
         */
        private int getBatchSize() {
        	int batchSize = groupBatchCount.isEmpty() ? 1 : groupBatchCount.get(curGroupID);
        	return batchSize;
        }
        
        /**
         * 
         */
        private void collectGroupItems() {
			DynamicBean item = new DynamicBean();
			item.set(ITEM_ID, items[1]);
			item.set(ITEM_COUNT, Integer.parseInt(items[countOrdinal]));
			item.set(ITEM_REWARD, Integer.parseInt(items[rewardOrdinal]));
			groupItems.add(item);
        }
        
        /**
         * @return
         * @throws InterruptedException 
         * @throws IOException 
         */
        private void select(Context context) throws IOException, InterruptedException {
			if (probRedAlgorithm.equals(PROB_RED_LINEAR )) {
				 linearSelect(context, false);
			} else if (probRedAlgorithm.equals(PROB_RED_LOG_LINEAR )) {
				 linearSelect(context, true);
			} else if (probRedAlgorithm.equals(DET_UBC1 )) {
				 deterministicAuerSelect(context);
			}
        }        
        
        /**
         * @return
         * @throws InterruptedException 
         * @throws IOException 
         */
        private void linearSelect(Context context, boolean logLinear) throws IOException, InterruptedException {
        	Set<String> items = new HashSet<String>();
        	int batchSize = getBatchSize();
        	int count = (roundNum -1) * batchSize;
        	String itemID = null;
        	float curProb;
        	
        	//select items for the batch
        	for (int i = 0; i < batchSize; ++i) {
        		++count;
        		if (logLinear) {
        			curProb = randomSelectionProb /count ;
        		} else {
        			curProb = (float)(randomSelectionProb * Math.log(count) / count);
        		}
            	itemID = linearSelectHelper(curProb, context);
            	while(!items.contains(itemID)) {
            		itemID = linearSelectHelper(curProb, context);
            	}
            	items.add(itemID);
        	}
        	
        	//emit all selected items
          	for (String item : items) {
    			outVal.set(curGroupID + fieldDelim + itemID);
        		context.write(NullWritable.get(), outVal);
          	}
        }
        
        /**
         * @param context
         * @throws IOException
         * @throws InterruptedException
         */
        private void deterministicAuerSelect(Context context) throws IOException, InterruptedException {
        	List<String> items = new ArrayList<String>();
        	int batchSize = getBatchSize();
        	int count = (roundNum -1) * batchSize;
    		int maxReward = 0;
    		String item = null;
			int thisCount;
    		int reward;

    		//max reward in this group
    		for (DynamicBean groupItem : groupItems) {
   				reward = groupItem.getInt(ITEM_REWARD);
    			if (reward > maxReward) {
    				maxReward = reward;
    			}
    		}

    		//collect items not tried before
    		ListIterator<DynamicBean> iter = groupItems.listIterator();
    		while (iter.hasNext()) {
    			DynamicBean groupItem = iter.next();
    			thisCount = groupItem.getInt(ITEM_COUNT);
    			if (thisCount == 0) {
    				//select  item that has not been tried before
    				item = groupItem.getString(ITEM_ID);
    				if (items.size() < batchSize) {
    					items.add(item);
    					iter.remove();
    					++count;
    				} else if (items.size() == batchSize) {
    					break;
    				}
    			} 
    		}
    		
    		//collect items according to UBC 
    		while (items.size() < batchSize) {
    			double valueMax = 0.0;
    			double value;
    			DynamicBean selectedGroupItem = null;
        		for (DynamicBean groupItem : groupItems) {
        			reward = groupItem.getInt(ITEM_REWARD);
        			thisCount = groupItem.getInt(ITEM_COUNT);
        			value = ((double)reward) / maxReward  +   Math.sqrt(2.0 * Math.log(count) / thisCount);
        			if (value > valueMax) {
        				item = groupItem.getString(ITEM_ID);
        				valueMax = value;
        				selectedGroupItem = groupItem;
        			}
        		}
        		
				items.add(item);
				groupItems.remove(selectedGroupItem);
				++count;
    		}
    		
        	//emit all selected items
          	for (String it : items) {
    			outVal.set(curGroupID + fieldDelim + it);
        		context.write(NullWritable.get(), outVal);
          	}
        	
        }
        
        /**
         * @param curProb
         * @param context
         * @throws IOException
         * @throws InterruptedException
         */
        private String linearSelectHelper(float curProb, Context context) throws IOException, InterruptedException {
        	String itemID = null;
        	if (curProb < Math.random()) {
        		//random
        		int select = (int)Math.round( Math.random() * groupItems.size());
        		select = select < groupItems.size() ? select : groupItems.size() -1; 
        		itemID = groupItems.get(select).getString(ITEM_ID);
        	} else {
        		//choose best so far
        		int maxReward = 0;
        		for (DynamicBean groupItem : groupItems) {
        			if (groupItem.getInt(ITEM_REWARD) > maxReward) {
        				maxReward = groupItem.getInt(ITEM_REWARD);
        				itemID = groupItem.getString(ITEM_ID);
        			}
        		}
        	}
			outVal.set(curGroupID + fieldDelim + itemID);
    		context.write(NullWritable.get(), outVal);
    		
    		return itemID;
        }
        
	}
	
	
    /**
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new GreedyRandomBandit(), args);
        System.exit(exitCode);
    }
	
}
