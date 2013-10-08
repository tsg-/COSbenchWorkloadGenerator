import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.text.NumberFormat;
import java.util.List;

public class COSbenchWorkloadGenerator {

	private Document doc;
	private String num_of_workers;
	private String runtime;
	private String output_path;
	private String delay;
	private String auth_url;
	private String rampup;
	private String num_of_drivers;

	public static void main(String argv[]) {

		if (argv.length < 1) {
			System.out.println("Usage: java " + System.getProperty("sun.java.command") + " <test_matrix_input_txt>");
			System.exit(1);
		}

		String test_matrix_filename = argv[0];

		COSbenchWorkloadGenerator xGen = new COSbenchWorkloadGenerator();

		BufferedReader br = null;

		Properties prop = new Properties();
		try {
			prop.load(new FileInputStream("config.properties"));
			xGen.num_of_workers = prop.getProperty("num_of_workers_per_stage");
			xGen.runtime = prop.getProperty("runtime");
			xGen.output_path = prop.getProperty("output_path")
				+ "/" + test_matrix_filename.substring(0, test_matrix_filename.lastIndexOf('.'))
				+ "/";
			xGen.delay = prop.getProperty("delay_between_main_stages");
			xGen.auth_url = prop.getProperty("auth_url");
			xGen.rampup = prop.getProperty("rampup");
			xGen.num_of_drivers = prop.getProperty("num_of_drivers");
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			return;
		} catch (IOException e1) {
			e1.printStackTrace();
			return;
		}

		try {

			String sCurrentLine;

			br = new BufferedReader(new FileReader(test_matrix_filename));

			while ((sCurrentLine = br.readLine()) != null) {

				String fields[] = sCurrentLine.split("\\t");
				String sizes[];
				int sizelength;
				boolean isRange;
				String unit = "KB";

				if (fields[1].contains("-")) {
					sizes = fields[1].substring(fields[1].indexOf("(") + 1,
							fields[1].indexOf(")")).split("-");
					unit = fields[1].substring(fields[1].indexOf(")") + 1);
					isRange = true;
				} else {
					sizes = fields[1].substring(fields[1].indexOf("(") + 1,
							fields[1].indexOf(")")).split(",");
					isRange = false;
				}
				String containers[] = fields[3].split(",");
				String readWriteDeletePercent[] = { "5,90,5", "45,50,5",
						"85,10,5", "98,1,1", "64,32,4" };
				String objects = fields[2];

				// isRange then set sized to just 1
				if (isRange)
					sizelength = 1;
				else
					sizelength = sizes.length;

				xGen.createXML(fields, sizes, containers, objects,
						readWriteDeletePercent, isRange, sizelength, unit);
			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if (br != null)
					br.close();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}

	}

	private static int roundUp(final int val)
	{
		double exponent = (int) Math.ceil(Math.log10(val));
		return (int) Math.pow(10, exponent);
	}

	public void createXML(String[] fields, String[] sizes, String[] containers,
			String objects, String[] readWriteDeletePercent, boolean isRange,
			int sizelength, String unit) {
		try {

			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			doc = docBuilder.newDocument();

			// workload element
			Element workload = doc.createElement("workload");
			doc.appendChild(workload);

			// workload attributes
			Attr attr = doc.createAttribute("name");
			attr.setValue(fields[0]);
			workload.setAttributeNode(attr);

			attr = doc.createAttribute("description");
			attr.setValue(fields[1]);
			workload.setAttributeNode(attr);

			// storage element
			Element storage = doc.createElement("storage");
			workload.appendChild(storage);

			// storage attributes
			attr = doc.createAttribute("type");
			attr.setValue("swift");
			storage.setAttributeNode(attr);

			// auth element
			Element auth = doc.createElement("auth");
			workload.appendChild(auth);

			// auth attributes
			attr = doc.createAttribute("type");
			attr.setValue("swauth");
			auth.setAttributeNode(attr);

			attr = doc.createAttribute("config");
			attr.setValue(auth_url);
			auth.setAttributeNode(attr);

			// workflow element
			Element workflow = doc.createElement("workflow");
			workload.appendChild(workflow);

			// multiple init and prepare stages		
			int numPrepWorkers = (Integer.valueOf(num_of_drivers) == 0) ? 1 
					: Integer.valueOf(objects) / Integer.valueOf(num_of_drivers);
			int nextPowerOf2 = (numPrepWorkers == 0) ? 0 
					: 32 - Integer.numberOfLeadingZeros(numPrepWorkers - 1);
			
			// limit at 11 (2048 workers max)
			numPrepWorkers = (nextPowerOf2 < 12) ? (1 << nextPowerOf2) : (1 << 11);
			numPrepWorkers = (numPrepWorkers < 4) ? 4 : numPrepWorkers;
			
			// create init stages
			int previousContainerValue = 0;
			for (int i = 0; i < sizelength; i++) {
				for (int j = 0; j < containers.length; j++) {
					String containerString = containers[j];
					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ Integer.valueOf(containerString) - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);
					createWorkstage(workflow, "init", num_of_drivers,
							"containers=r(" + from_container + ","
									+ to_container + ")", -1);
				}
			}
			
			// Determine the number of read/write/delete combinations
			int numRWDCombinations = 0;
			for (int k = 0; k < readWriteDeletePercent.length; k++) {
				if ((k + 4) > fields.length - 1
						|| fields[k + 4].length() == 0) {
					continue;
				}
				++numRWDCombinations;
			}

			// create prepare stages
			previousContainerValue = 0;
			int previousObjectValue = 0;
			int num_containers = 0;
			int num_objects = 0;
			int num_objects_per_container = 0;

			for (int i = 0; i < sizelength; i++) {
				for (int j = 0; j < containers.length; j++) {
					String sizeString;
					if (isRange)
						sizeString = "(" + sizes[0] + "," + sizes[1] + ")"
								+ unit;
					else
						sizeString = "("
								+ sizes[i].substring(0, sizes[i].length() - 2)
								+ ")"
								+ sizes[i].substring(sizes[i].length() - 2);

					String containerString = containers[j];
					num_containers = Integer.valueOf(containerString);
					num_objects = Integer.valueOf(objects);
					num_objects_per_container = num_objects;

					String from_container = previousContainerValue
							+ num_containers + "";
					String to_container = Integer.valueOf(from_container)
							+ num_containers - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);

					// 1 set of objects for read/writes
					// n sets of objects for deletes
					String from_object = previousObjectValue + 1 + "";
					String to_object = Integer.valueOf(from_object)
							+ ((num_objects_per_container) * 
							  (numRWDCombinations + 1)) - 1 + ""; // 1+n times the number of objects
					previousObjectValue = Integer.valueOf(to_object);

					if (isRange)
						createWorkstage(
								workflow,
								"prepare",
								numPrepWorkers + "",
								"containers=r(" + from_container + ","
										+ to_container + ");objects=r("
										+ from_object + "," + to_object
										+ ");sizes=u" + sizeString,
								num_objects_per_container * num_containers);
					else
						createWorkstage(
								workflow,
								"prepare",
								numPrepWorkers + "",
								"containers=r(" + from_container + ","
										+ to_container + ");objects=r("
										+ from_object + "," + to_object
										+ ");sizes=c" + sizeString,
								num_objects_per_container * num_containers);
				}
			}

			// multiple main stages
			previousContainerValue = 0;
			previousObjectValue = 0;
			int previousDeleteObjectValue = 0;
			for (int i = 0; i < sizelength; i++) {
				for (int j = 0; j < containers.length; j++) {
					String sizeString;
					if (isRange)
						sizeString = "(" + sizes[0] + "," + sizes[1] + ")"
								+ unit;
					else
						sizeString = "("
								+ sizes[i].substring(0, sizes[i].length() - 2)
								+ ")"
								+ sizes[i].substring(sizes[i].length() - 2);

					String containerString = containers[j];

					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ Integer.valueOf(containerString) - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);

					num_containers = Integer.valueOf(to_container) - Integer.valueOf(from_container) + 1;
					num_objects = Integer.valueOf(objects);
					num_objects_per_container = num_objects;

					String from_object = previousObjectValue + 1 + "";
					String to_object = Integer.valueOf(from_object)
							+ ((num_objects_per_container) - 1) + "";
					
					for (int k = 0; k < readWriteDeletePercent.length; k++) {
						if ((k + 4) > fields.length - 1
								|| fields[k + 4].length() == 0) {
							continue;
						}

						String readRatio = readWriteDeletePercent[k].split(",")[0];
						String writeRatio = readWriteDeletePercent[k]
								.split(",")[1];
						String deleteRatio = readWriteDeletePercent[k]
								.split(",")[2];

						if (previousDeleteObjectValue == 0)
							previousDeleteObjectValue = Integer.valueOf(to_object);
						else
							previousDeleteObjectValue += num_objects_per_container;

						createNormalWorkstage(fields[1].substring(0, fields[1].indexOf("(")),
								workflow, num_of_workers, runtime, readRatio,
								writeRatio, deleteRatio, isRange,
								from_container, to_container, num_objects_per_container,
								from_object, to_object, previousDeleteObjectValue + 1, sizeString);
					}

					previousObjectValue = previousDeleteObjectValue + (num_objects_per_container);
					previousDeleteObjectValue = 0;
				}			
			}
		
/*
			// multiple cleanup stages
			previousContainerValue = 0;
			previousObjectValue = 0;
			for (int i = 0; i < sizelength; i++) {
				for (int j = 0; j < containers.length; j++) {
					String containerString = containers[j];
					num_containers = Integer.valueOf(containerString);
					num_objects = Integer.valueOf(objects);
					num_objects_per_container = num_objects;

					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ (Integer.valueOf(containerString) - 1) + "";
					previousContainerValue = Integer.valueOf(to_container);

					String from_object = (previousObjectValue + 1) + "";
					String to_object = Integer.valueOf(from_object)
							+ (((num_objects_per_container) * (numRWDCombinations + 1)) - 1) + "";
					previousObjectValue = Integer.valueOf(to_object);

					createWorkstage(workflow, "cleanup", numPrepWorkers + "", "containers=r("
							+ from_container + "," + to_container
							+ ");objects=r(" + from_object + "," + to_object
							+ ")", -1);

				}
			}

			// multiple dispose stages
			previousContainerValue = 0;
			for (int i = 0; i < sizelength; i++) {
				for (int j = 0; j < containers.length; j++) {
					String containerString = containers[j];
					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ Integer.valueOf(containerString) - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);
					createWorkstage(workflow, "dispose", Integer.valueOf(num_of_drivers) + "", "containers=r("
							+ from_container + "," + to_container + ")", -1);
				}
			}
*/

			// write the content into xml file

			TransformerFactory transformerFactory = TransformerFactory
					.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty(
					"{http://xml.apache.org/xslt}indent-amount", "10");
			DOMSource source = new DOMSource(doc);
			// StreamResult result = new StreamResult(new
			// File(output_path+fields[0].replaceAll("\\s+",
			// "")+"-"+fields[1].substring(0,fields[1].indexOf("(")).replaceAll("\\s+",
			// "")+"-"+sizeString.replaceAll("[()]",
			// "")+"-"+objectString+"-"+containerString+"-template"+".xml"));
			File f = new File(output_path);
			f.mkdirs();

			f = new File(output_path + fields[0].replaceAll("\\s+", "") + ".xml");
			StreamResult result = new StreamResult(f);

			// Output to console for testing
			// StreamResult result = new StreamResult(System.out);

			transformer.transform(source, result);

		} catch (ParserConfigurationException pce) {
			pce.printStackTrace();
		} catch (TransformerException tfe) {
			tfe.printStackTrace();
		}

	}

	private void createNormalWorkstage(String workloadType, Element workflow,
			String workers, String runtime, String readRatio,
			String writeRatio, String deleteRatio, boolean isRange,
			String from_container, String to_container, int objects, 
			String from_object, String to_object, int previousDeleteObjectValue, 
			String sizeString) {
		

		Attr attr;

		Element workstage = doc.createElement("workstage");
		workflow.appendChild(workstage);

		String stageName = "main-"
				+ workloadType.replaceAll("\\s+", "")
				+ sizeString
				+ "_c"
				+ (Integer.valueOf(to_container)
						- Integer.valueOf(from_container) + 1)
				+ "_o"
				+ (Integer.valueOf(to_object) - Integer.valueOf(from_object) + 1)
				+ "_r" + readRatio + "w" + writeRatio + "d" + deleteRatio
				+ "_NUMWORKERS";

		attr = doc.createAttribute("name");
		attr.setValue(stageName);
		workstage.setAttributeNode(attr);

		attr = doc.createAttribute("closuredelay");
		attr.setValue(delay);
		workstage.setAttributeNode(attr);

		Element work = doc.createElement("work");
		workstage.appendChild(work);

		attr = doc.createAttribute("name");
		attr.setValue(stageName);
		work.setAttributeNode(attr);

		attr = doc.createAttribute("workers");
		attr.setValue(workers);
		work.setAttributeNode(attr);

		attr = doc.createAttribute("rampup");
		attr.setValue(rampup);
		work.setAttributeNode(attr);

		attr = doc.createAttribute("runtime");
		attr.setValue(runtime);
		work.setAttributeNode(attr);

		// Read operation
		Element operation = doc.createElement("operation");
		work.appendChild(operation);

		attr = doc.createAttribute("type");
		attr.setValue("read");
		operation.setAttributeNode(attr);

		attr = doc.createAttribute("ratio");
		attr.setValue(readRatio);
		operation.setAttributeNode(attr);

		attr = doc.createAttribute("config");
		attr.setValue("containers=u(" + from_container + "," + to_container
				+ ");objects=u(" + from_object + "," + to_object + ")");
		operation.setAttributeNode(attr);

		// Write operation
		operation = doc.createElement("operation");
		work.appendChild(operation);

		attr = doc.createAttribute("type");
		attr.setValue("write");
		operation.setAttributeNode(attr);

		attr = doc.createAttribute("ratio");
		attr.setValue(writeRatio);
		operation.setAttributeNode(attr);

		if (isRange) {
			attr = doc.createAttribute("config");
			attr.setValue("containers=u(" + from_container + "," + to_container
					+ ");objects=u(" + from_object + "," + to_object
					+ ");sizes=u" + sizeString);
			operation.setAttributeNode(attr);
		} else {
			attr = doc.createAttribute("config");
			attr.setValue("containers=u(" + from_container + "," + to_container
					+ ");objects=u(" + from_object + "," + to_object
					+ ");sizes=c" + sizeString);
			operation.setAttributeNode(attr);
		}

		// Delete operation
		from_object = previousDeleteObjectValue + "";
		to_object = previousDeleteObjectValue + (objects - 1) + "";
		
		operation = doc.createElement("operation");
		work.appendChild(operation);

		attr = doc.createAttribute("type");
		attr.setValue("delete");
		operation.setAttributeNode(attr);

		attr = doc.createAttribute("ratio");
		attr.setValue(deleteRatio);
		operation.setAttributeNode(attr);

		attr = doc.createAttribute("config");
		attr.setValue("containers=u(" + from_container + "," + to_container
				+ ");objects=u(" + from_object + "," + to_object + ")");
		operation.setAttributeNode(attr);
	}

	public void createWorkstage(Element workflow, String name, String workers,
			String config, int totalOps) {
		Attr attr;

		String configStr = config;
		String stageName = name
				+ configStr.replaceAll("containers=", "-c")
					   .replaceAll(";objects=", "-o")
					   .replaceAll(";sizes=", "-s")
				+ "-" + workers;

		Element workstage = doc.createElement("workstage");
		workflow.appendChild(workstage);

		attr = doc.createAttribute("name");
		attr.setValue(stageName);
		workstage.setAttributeNode(attr);

		Element work = doc.createElement("work");
		workstage.appendChild(work);

		attr = doc.createAttribute("type");
		attr.setValue(name);
		work.setAttributeNode(attr);

		attr = doc.createAttribute("workers");
		if (Integer.valueOf(workers) > 4096)
			workers = "4096";
		attr.setValue(workers);
		work.setAttributeNode(attr);

		if (totalOps > 0) {
			attr = doc.createAttribute("totalOps");
			attr.setValue(Integer.toString(totalOps));
			work.setAttributeNode(attr);
		}

		attr = doc.createAttribute("config");
		attr.setValue(config);
		work.setAttributeNode(attr);

	}
}
