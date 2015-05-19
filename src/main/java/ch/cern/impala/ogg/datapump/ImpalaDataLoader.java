package ch.cern.impala.ogg.datapump;

import java.io.IOException;
import java.sql.SQLException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.cern.impala.ogg.datapump.impala.ImpalaClient;
import ch.cern.impala.ogg.datapump.impala.Query;
import ch.cern.impala.ogg.datapump.impala.QueryBuilder;
import ch.cern.impala.ogg.datapump.impala.descriptors.StagingTableDescriptor;
import ch.cern.impala.ogg.datapump.impala.descriptors.TableDescriptor;
import ch.cern.impala.ogg.datapump.oracle.ControlFile;
import ch.cern.impala.ogg.datapump.utils.PropertiesE;

public class ImpalaDataLoader {

	final private static Logger LOG = LoggerFactory.getLogger(ImpalaDataLoader.class);

	/**
	 * Maximum milliseconds between batches
	 */
	private static final long MAX_MS_BETWEEN_BATCHES = 10 * 60 * 1000;

	private long ms_between_batches;

	private ControlFile sourceControlFile;

	private LocalFileSystem local;
	private FileSystem hdfs;

	private Path stagingHDFSDirectory;

	private Query createStagingTable;
	private Query dropStagingTable;
	private Query insertInto;
	private Query createTargetTable;

	public ImpalaDataLoader(PropertiesE prop) throws Exception {

		// Get source table descriptor
		TableDescriptor sourceTableDes = TableDescriptor.createFromFile(prop.getDefinitionFile());
		LOG.debug("source " + sourceTableDes.toString());

		// Apply custom configuration to get the target table descriptor
		TableDescriptor targetTableDes = (TableDescriptor) sourceTableDes.clone();
		targetTableDes.applyCustomConfiguration(prop);

		// Get staging table descriptor
		StagingTableDescriptor stagingTableDes = sourceTableDes.getDefinitionForStagingTable();
		stagingTableDes.applyCustomConfiguration(prop);

		// Get file systems
		Configuration conf = new Configuration();
		conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
		hdfs = FileSystem.get(conf);
		conf.set("fs.file.impl", LocalFileSystem.class.getName());
		local = FileSystem.getLocal(conf);

		// Perform test on staging directory
		stagingHDFSDirectory = prop.getStagingHDFSDirectory(
								targetTableDes.getSchemaName(), targetTableDes.getTableName());
		stagingHDFSDirectory = testStagingDirectory(hdfs, stagingHDFSDirectory);

		ImpalaClient impalaClient = new ImpalaClient(prop.getImpalaHost(), prop.getImpalaPort());
		QueryBuilder queryBuilder = impalaClient.getQueryBuilder();

		// Get query for creating staging table
		String createStagingTableQuery_prop = prop.getCreateStagingTableQuery();
		if (createStagingTableQuery_prop == null) {
			createStagingTable = queryBuilder.createExternalTable(stagingTableDes, stagingHDFSDirectory);

			LOG.info("staging " + stagingTableDes);
		} else {
			createStagingTable = new Query(createStagingTableQuery_prop, impalaClient);
			LOG.info("query to create staging table set to: " + createStagingTable);
		}
		
		// Get query for dropping staging table
		String dropStagingTableQuery_prop = prop.getDropStagingTableQuery();
		if (dropStagingTableQuery_prop == null) {
			dropStagingTable = queryBuilder.dropTable(stagingTableDes);
		} else {
			dropStagingTable = new Query(dropStagingTableQuery_prop, impalaClient);
			LOG.info("query to drop staging table set to: " + dropStagingTable);
		}

		// Get query for importing data from staging table to final table
		String insertIntoQuery_prop = prop.getInsertIntoQuery();
		if (insertIntoQuery_prop == null) {
			insertInto = queryBuilder.insertInto(stagingTableDes, targetTableDes);
		} else {
			insertInto = new Query(insertIntoQuery_prop, impalaClient);
			LOG.info("insert query set to: " + insertInto);
		}

		// Get query for creating target table
		String createTargetTableQuery_prop = prop.getCreateTableQuery();
		if (createTargetTableQuery_prop == null) {
			createTargetTable = queryBuilder.createTable(targetTableDes);

			LOG.info("target " + targetTableDes);
		} else {
			createTargetTable = new Query(createTargetTableQuery_prop, impalaClient);
			LOG.info("create target table query set to: " + createTargetTable);
		}

		// Create target table if it does not exist
		try {
			createTargetTable.exect();
			LOG.info("created final table");
		} catch (SQLException e) {
			if (!e.getMessage().contains("Table already exists:")) {
				LOG.error("final table could not be created", e);
				throw e;
			}
		}

		// Get control file which is generated by OGG
		sourceControlFile = prop.getSourceContorlFile(stagingTableDes);
		LOG.info("reading control data from " + sourceControlFile);

		// Period of time for checking new data
		ms_between_batches = prop.getTimeBetweenBatches();
	}

	private void start() throws Exception {
		
		// Check periodically for new data
		while (true) {
			long startTime = System.currentTimeMillis();

			// Control file which contains the list of files to process in this batch
			ControlFile controlFile = sourceControlFile.getControlFileToProcess();

			if (controlFile != null) {
				LOG.info("there is new data to process");

				Batch batch = new Batch(local, 
										hdfs, 
										controlFile,
										stagingHDFSDirectory, 
										dropStagingTable,
										createStagingTable, 
										insertInto);
				batch.start();
				batch.clean();
			} else {
				LOG.info("there is no data to process");
			}

			waitForNextBatch(startTime, ms_between_batches);
		}
	}

	private void waitForNextBatch(long startTime, long ms_between_batches) {
		long timeDiff = System.currentTimeMillis() - startTime;

		long waitTime = Math.min(ms_between_batches - timeDiff,
				MAX_MS_BETWEEN_BATCHES - timeDiff);

		LOG.info("waiting " + (waitTime / 1000) + " seconds...");

		while (timeDiff < ms_between_batches) {
			if (timeDiff > MAX_MS_BETWEEN_BATCHES) {
				LOG.warn("the maximun time between batches ("
						+ (ms_between_batches / 1000)
						+ " seconds) has been achieved.");

				return;
			}

			timeDiff = System.currentTimeMillis() - startTime;
		}
	}

	/**
	 * Check if the staging directory can be created and deleted
	 * 
	 * NOTE: If the directory exists, it will be deleted (and all the contained content)
	 * 
	 * @param hdfs File system
	 * @param directory Future staging directory to check
	 * @return Resolved directory
	 * @throws IOException
	 */
	private Path testStagingDirectory(FileSystem hdfs, Path directory)
			throws IOException {
		
		if (hdfs.exists(directory)) {			
			IllegalStateException e = new IllegalStateException(
					"the staging directory (" + directory + ") must be removed");
			LOG.error(e.getMessage(), e);
			throw e;
		}
		
		if (!hdfs.mkdirs(directory)) {
			IllegalStateException e = new IllegalStateException(
					"target directory could not be created");
			LOG.error(e.getMessage(), e);
			throw e;
		}

		Path stagingDirectory = hdfs.resolvePath(directory);

		if (!hdfs.delete(directory, true)) {
			IllegalStateException e = new IllegalStateException(
					"target directory could not be deleted");
			LOG.error(e.getMessage(), e);
			throw e;
		}

		return stagingDirectory;
	}

	public static void main(String[] args) throws Exception {
		String prop_file = args == null || args.length != 1 || args[0] == null ? 
				PropertiesE.DEFAULT_PROPETIES_FILE : args[0];
		
		LOG.info("inicializing loader (properties file = " + prop_file + ")");

		// Load properties file
		PropertiesE prop = new PropertiesE(prop_file);
		
		//Create and start loader
		ImpalaDataLoader loader = new ImpalaDataLoader(prop);
		loader.start();
	}
}