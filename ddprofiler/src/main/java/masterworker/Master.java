package masterworker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClients;

import comm.WebServer;
import core.Catalog;
import core.Conductor;
import core.TaskPackage;
import core.config.ProfilerConfig;

public class Master {

	private ProfilerConfig pc;
	private Conductor c;
	private String dbName;

	private boolean pendingWork;
	// workers
	private HashMap<String, WorkerStatus> workers;

	// tasks
	private Map<Integer, TaskPackage> tasks;

	// completion of tasks
	private Map<Integer, TaskStatus> taskToStatus;

	// catalog
	private Catalog catalog;

	public Master(ProfilerConfig pc, Conductor c) {
		this.pc = pc;
		this.c = c;
		this.workers = new HashMap<String, WorkerStatus>();
		this.dbName = pc.getString(ProfilerConfig.DB_NAME);
		this.tasks = new HashMap<Integer, TaskPackage>();
		this.taskToStatus = new HashMap<Integer, TaskStatus>();

		// Setup catalog

		File catalogFile = new File("tasks.catalog");
		FileInputStream fin = null;
		ObjectInputStream ois = null;
		try {
			if (catalogFile.exists()) {
				fin = new FileInputStream(catalogFile);
				ois = new ObjectInputStream(fin);

				this.catalog = (Catalog) ois.readObject();
			} else {
				this.catalog = new Catalog();
			}
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		} finally {
			if (fin != null) {
				try {
					fin.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			if (ois != null) {
				try {
					ois.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

	}

	public void start(String sourcePath) {
		pendingWork = true;

		String dbName = pc.getString(ProfilerConfig.DB_NAME);
		String pathToSources = pc.getString(ProfilerConfig.SOURCES_TO_ANALYZE_FOLDER);
		String separator = pc.getString(ProfilerConfig.CSV_SEPARATOR);
		// split up work
		readDirectoryAndCreateTasks(dbName, c, pathToSources, separator);

		for (Integer taskId : tasks.keySet()) {
			if (catalog.taskCompleted(taskId)) {
				// see if user wants to re-execute, otherwise, ignore
			}
		}

		Master master = this;

		Thread server = new Thread() {
			public void run() {
				WebServer ws = new WebServer(pc, c, master);
				ws.init();
			}
		};

		server.start();

		while (pendingWork) {
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		stopWorkers();
	}

	private void stopWorkers() {
		for (String workerAddr : workers.keySet()) {
			// if (workers.get(workerAddr).isRunning()) check worker status
			stopWorker(workerAddr);
		}
	}

	private void stopWorker(String workerAddr) {

		HttpClient httpclient = HttpClients.createDefault();

		URIBuilder builder = new URIBuilder();
		builder.setScheme("http")
		.setHost("localhost:" + workerAddr)
		.setPath("/dd")
		.setParameter("actionid", "stopWorker");

		// Execute and get the response.
		HttpGet httpget;
		try {
			httpget = new HttpGet(builder.build());
			Object response = httpclient.execute(httpget);// try to connect to
															// worker
		} catch (URISyntaxException | IOException e) {
			e.printStackTrace();
		}
	}

	public String registerWorker(String workerAddr) {
		WorkerStatus newWorkerStatus = new WorkerStatus(false);
		workers.put(workerAddr, newWorkerStatus);
		// give worker chunk of work to process
		for (Integer taskId : tasks.keySet()) {
			if (taskToStatus.get(taskId) == TaskStatus.INCOMPLETE) {// check if this task still needs to be
											// done
				processPathOnWorker(taskId, workerAddr);
				taskToStatus.put(taskId, TaskStatus.PENDING);
				return "OK";
			}
		}
		// no more work to do
		return "OK";
	}

	public String taskComplete(String workerAddr, String taskIds) {
		// update worker status
		Scanner scanner = new Scanner(taskIds);
    	while (scanner.hasNextInt()) {
    		int taskId = scanner.nextInt();
    		taskToStatus.put(taskId, TaskStatus.COMPLETE);
    		catalog.addTask(taskId, tasks.get(taskId));

    	}
    	scanner.close();
    	
		//workers.get(workerAddr).updateStatus(false);

		// mark task complete
		for (int newTaskId : tasks.keySet()) {
			if (taskToStatus.get(newTaskId) == TaskStatus.INCOMPLETE) {// check if this task still needs to be done
				processPathOnWorker(newTaskId, workerAddr);
				taskToStatus.put(newTaskId, TaskStatus.PENDING);
				return "OK";
			}
		}
		// no more work to do
		pendingWork = false;
		return "OK";
	}

	private void processPathOnWorker(int taskId, String workerAddr) {

		workers.get(workerAddr).addTask(taskId);// add to list of tasks worker is/has processed
		TaskPackage task = tasks.get(taskId);

		URLConnection conn = null;
		Object reply = null;
		try {
			System.out.println("trying to process path on worker");
			URIBuilder builder = new URIBuilder();
			builder.setScheme("http").setHost("localhost:" + workerAddr).setPath("/dd").setParameter("actionid",
					"processTaskOnWorker");
			//
			URL url = builder.build().toURL();
			//
			conn = url.openConnection();
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			// // send object
			ObjectOutputStream objOut = new ObjectOutputStream(conn.getOutputStream());
			objOut.writeObject(task);
			objOut.flush();
			objOut.close();

		} catch (IOException | URISyntaxException ex) {
			ex.printStackTrace();
		}
		// recieve reply
		try {
			ObjectInputStream objIn = new ObjectInputStream(conn.getInputStream());
			reply = objIn.readObject();
			objIn.close();
		} catch (Exception ex) {
			// it is ok if we get an exception here
			// that means that there is no object being returned		
			}
	}

	private void readDirectoryAndCreateTasks(String dbName, Conductor c, String pathToSources, String separator) {
		File folder = new File(pathToSources);
		File[] filePaths = folder.listFiles();
		int totalFiles = 0;
		int tt = 0;
		for (File f : filePaths) {
			tt++;
			if (f.isFile()) {
				String path = f.getParent() + File.separator;
				String name = f.getName();
				TaskPackage tp = TaskPackage.makeCSVFileTaskPackage(dbName, path, name, separator);
				totalFiles++;
				tasks.put(tp.getId(), tp);
				taskToStatus.put(tp.getId(), TaskStatus.INCOMPLETE);// check catalog
				// c.submitTask(tp);
				if (catalog.taskCompleted(tp.getId())) {
					
				}
			}
		}
		// LOG.info("Total files submitted for processing: {} - {}", totalFiles,
		// tt);
	}
	
	private enum TaskStatus {
		COMPLETE,
		INCOMPLETE,
		PENDING;
	}
}
