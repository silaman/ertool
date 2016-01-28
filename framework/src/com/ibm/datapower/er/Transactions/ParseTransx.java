/**
 * Copyright 2014-2016 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package com.ibm.datapower.er.Transactions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.ibm.datapower.er.ERFramework;
import com.ibm.datapower.er.ErrorReportDetails;
import com.ibm.datapower.er.IPartInfo;
import com.ibm.datapower.er.ReportProcessorPartInfo;

public class ParseTransx extends ERFramework {

	// file for loading the rules for what log messages we parse
	protected String mTransactionRules = "dptransx.xml";
	// these are the types of rules we have loaded
	protected ArrayList<LogType> mTypes = new ArrayList<LogType>();
	// this is our result output and holding of transactional data
	protected TransactionHistory mHistory = new TransactionHistory();
	protected Document mDocument = null;

	public ParseTransx() {

	}

	public void SetTransactionRulesFile(String file) {
		mTransactionRules = file;
	}

	// pull the xml document
	protected Document loadTransactionsRules(String filename) {
		if (mDocument != null)
			return mDocument;

		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		Document doc;
		try {

			InputStream is = new FileInputStream(filename);
			dBuilder = dbFactory.newDocumentBuilder();
			doc = dBuilder.parse(is);
			doc.getDocumentElement().normalize();
			mDocument = doc;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return mDocument;
	}

	// load the log settings into mTypes
	protected void loadTypes() {
		mTypes.clear();

		Document doc = loadTransactionsRules(mTransactionRules);

		if (doc == null) {
			System.out.println("No document defined to load (dptransx.xml).");
			return;
		}

		Element baseElement = (Element) doc
				.getElementsByTagName("Transactions").item(0);

		Element logTypesElement = (Element) baseElement.getElementsByTagName(
				"LogTypes").item(0);

		// iterate through the log types
		NodeList nodes = logTypesElement.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i).getNodeType() != Node.ELEMENT_NODE)
				continue;

			Element logTypeElement = (Element) nodes.item(i);

			// pull the regular expression formula we use to match
			NodeList regExpNodes = logTypeElement
					.getElementsByTagName("RegEXP");
			String regEXP = "";
			if (regExpNodes.getLength() > 0)
				regEXP = (String) regExpNodes.item(0).getTextContent();

			LogType logType = new LogType(regEXP);
			mTypes.add(logType);

			// load the fields and their positions
			try {
				NodeList fieldNodes = logTypeElement
						.getElementsByTagName("Fields").item(0).getChildNodes();
				for (int f = 0; f < fieldNodes.getLength(); f++) {
					if (fieldNodes.item(f).getNodeType() != Node.ELEMENT_NODE)
						continue;

					Element fieldElement = (Element) fieldNodes.item(f);

					String position = fieldElement.getAttribute("Position");

					int pos = 0;
					try {
						pos = Integer.parseInt(position);
					} catch (Exception ex) {
					}

					String name = fieldElement.getAttribute("Name");

					logType.addField(pos, name.toLowerCase());
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	public void doParse(String outFile, String timeFormat) {
		mHistory.setLogFormat(timeFormat);

		// load our rules
		loadTypes();

		try {
			// find all cid's with "log" in them
			ArrayList<String> matches = getMatchesToCid("log");
			for (int i = 0; i < matches.size(); i++) {
				// pull the section using the CID
				InputStream is = getCidAsInputStream(matches.get(i), true);

				// create ReportProcessorPartInfo so we can pull the MIME out
				HashMap headers = new HashMap<String, String>();
				headers.put("Content-ID", matches.get(i));
				ErrorReportDetails details = new ErrorReportDetails();
				ReportProcessorPartInfo partInfo = new ReportProcessorPartInfo(
						IPartInfo.MIME_BODYPART, headers, is, details);
				InputStream resStream = partInfo.getBodyStream();

				// take the line buffer so we can read it out
				BufferedReader bis = new BufferedReader(new InputStreamReader(
						resStream));
				String nextLine = "";
				while ((nextLine = bis.readLine()) != null) {
					// we have our line lets see what it can match to
					for (int f = 0; f < mTypes.size(); f++) {
						LogType logType = mTypes.get(f);
						HashMap strList = parseRegExp(logType.getRegEXP(),
								nextLine, logType);

						// if we have a match the list will be populated
						if (strList.size() > 0) {
							handleTransactionLine(strList, logType);
							break;
						}
					} // end for
				} // end while
			} // end try
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// out stream for result
		PrintStream stream = null;
		try {
			String dir = "";
			// if a file name was passed to the tool we can use that
			if (outFile.length() > 0) {
				File file = new File(outFile);
				File parentDir = file.getParentFile(); // get parent dir
				dir = parentDir.getPath();
				if (dir.contains(":\\"))
					dir += "\\";
				else
					dir += "/";
				// we rename to transactions.txt because outfile is used for
				// analytics
				stream = new PrintStream(new FileOutputStream(dir
						+ "transactions.txt", false));
			} else
				// we use the system console
				stream = new PrintStream(System.out);

			// print results to stream
			parseResults(stream, dir);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (stream != null)
				stream.close();
		}
	}

	public void parseResults(PrintStream stream, String dir) {
		// get our transaction history and pull it all out
		HashMap map = mHistory.getTransactionMap();
		Collection c = map.values();
		Iterator itr = c.iterator();
		ArrayList<Transaction> listToSort = new ArrayList<Transaction>();

		while (itr.hasNext()) {
			listToSort.add((Transaction) itr.next());
		}

		// order transactions by date
		Collections.sort(listToSort, new TransactionSort());

		// print results
		for (int i = 0; i < listToSort.size(); i++) {
			Transaction transx = listToSort.get(i);

			// we order log messages AFTER recording all the messages related to
			// the transaction
			transx.OrderTransactions();

			stream.println("Transaction ID: " + transx.getTransID()
					+ ", LogLevel: " + transx.getLogLevel() + ", LogType: "
					+ transx.getLogType());

			stream.println("Start Time: " + transx.getTranslatedStartTime()
					+ ", End Time: " + transx.getTranslatedEndTime());
			StringBuffer bfr = transx.getMessageBuffer();
			stream.println(bfr);
			stream.println();
		}

		TransxXML.writeTransactionsToXml(this, listToSort, dir);
	}

	protected boolean handleTransactionLine(HashMap strList, LogType matchType) {
		String id = (String) strList.get("id");
		if (id == null || id == "") {
			System.out.println("ID was not set");
			return false;
		}

		String msg = (String) strList.get("message");
		if (msg == null || msg == "") {
			System.out.println("MSG was not set");
			return false;
		}

		String timestamp = (String) strList.get("timestamp");
		if (timestamp == null || timestamp == "") {
			System.out.println("TIMESTAMP was not set");
			return false;
		}

		mHistory.AppendTransaction(id, msg, strList);

		return true;
	}

	protected HashMap parseRegExp(String regexp, String line, LogType logType) {
		HashMap<String, String> map = new HashMap<String, String>();

		Pattern pattern = Pattern.compile(regexp);

		Matcher matcher = pattern.matcher(line);

		while (matcher.find()) {
			for (int c = 0; c < logType.getFields().size(); c++) {
				LogField field = logType.getFields().get(c);
				try {
					String value = matcher.group(field.getPosNum());
					map.put(field.getFieldName(), value);
				} catch (Exception ex) {
					continue;
				}
			}

		}

		return map;
	}
}