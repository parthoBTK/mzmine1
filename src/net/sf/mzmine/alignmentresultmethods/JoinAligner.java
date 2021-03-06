/*
    Copyright 2005 VTT Biotechnology

    This file is part of MZmine.

    MZmine is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    MZmine is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with MZmine; if not, write to the Free Software
    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/

package net.sf.mzmine.alignmentresultmethods;

import net.sf.mzmine.alignmentresultmethods.*;
import net.sf.mzmine.alignmentresultvisualizers.*;
import net.sf.mzmine.datastructures.*;
import net.sf.mzmine.distributionframework.*;
import net.sf.mzmine.miscellaneous.*;
import net.sf.mzmine.peaklistmethods.*;
import net.sf.mzmine.rawdatamethods.*;
import net.sf.mzmine.rawdatavisualizers.*;
import net.sf.mzmine.userinterface.*;

// Java packages
import java.util.*;
import java.util.Vector;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;

import javax.swing.*;
import java.awt.*;
import java.text.*;
import java.awt.event.*;




/**
 *
 */
public class JoinAligner implements PeakListAligner {

	private JoinAlignerParameters parameters;

	/**
	 * This method asks user to define which raw data files should be aligned and also check parameter values
	 */
	public JoinAlignerParameters askParameters(MainWindow mainWin, JoinAlignerParameters currentValues) {

		JoinAlignerParameters myParameters;
		if (currentValues==null) {
			myParameters = new JoinAlignerParameters();
		} else {
			myParameters = currentValues;
		}

		JoinAlignerParameterSetupDialog jaPSD = new JoinAlignerParameterSetupDialog(mainWin, new String("Please give parameter values"), myParameters);
		jaPSD.setLocationRelativeTo(mainWin);
		jaPSD.setVisible(true);

		// Check if user pressed cancel
		if (jaPSD.getExitCode()==-1) {
			return null;
		}

		myParameters = jaPSD.getParameters();

		return myParameters;
	}


	/**
	 * This function aligns peak lists of selected group of raw data files
	 */
	// public AlignmentResult doAlignment(MainWindow _mainWin) {
	 public AlignmentResult doAlignment(NodeServer nodeServer, Hashtable<Integer, PeakList> peakLists, PeakListAlignerParameters _parameters) {


		parameters = (JoinAlignerParameters)_parameters;


		// Translate peak lists to isotope lists
		// -------------------------------------

		// Data structure for storing isotope lists
		Hashtable<Integer, Hashtable<Integer, IsotopePattern>> isotopeLists = new Hashtable<Integer, Hashtable<Integer, IsotopePattern>>();

		// Loop through the peak lists, and collect peaks to isotope patterns
		Enumeration<Integer> rawDataIDEnum = peakLists.keys();
		Enumeration<PeakList> peakListEnum = peakLists.elements();
		while (rawDataIDEnum.hasMoreElements()) {

			// Pickup next rawDataID and all peaks for that raw data
			Integer rawDataID = rawDataIDEnum.nextElement();
			Vector<Peak> peakList = peakListEnum.nextElement().getPeaks();

			// Initialize isotope list
			Hashtable<Integer, IsotopePattern> isotopeList = new Hashtable<Integer, IsotopePattern>();

			// Find maximum used isotope pattern number (information is needed for filling unassigned isotope numbers)
			int nextUnassignedIsotopePatternID=-1;
			for (Peak p : peakList) {
				if ( p.getIsotopePatternID()>nextUnassignedIsotopePatternID ) { nextUnassignedIsotopePatternID = p.getIsotopePatternID(); }
			}
			nextUnassignedIsotopePatternID++;

			// Loop through all peaks in this raw data
			for (Peak p : peakList) {
				// Get isotope pattern ID for this peak
				Integer isotopePatternID = new Integer(p.getIsotopePatternID());

				// If isotope Pattern ID is unassigned (-1), then this peak must be assigned to a new isotope pattern
				if (isotopePatternID==-1) {
					isotopePatternID = new Integer(nextUnassignedIsotopePatternID);
					nextUnassignedIsotopePatternID++;
				}


				// Check if there is an exisiting isotope pattern object for this ID and create a new pattern if there isn't one already
				IsotopePattern isotopePattern = isotopeList.get(isotopePatternID);
				if (isotopePattern==null) {
					isotopePattern = new IsotopePattern(rawDataID.intValue());
					isotopeList.put(isotopePatternID, isotopePattern);
				}

				// Add this peak to the isotope pattern
				isotopePattern.addPeak(p);
			}

			// Store the isotope list
			isotopeLists.put(rawDataID, isotopeList);

		}

		System.gc();

		// Initialize master isotope list
		// ------------------------------
		Vector<MasterIsotopeListRow> masterIsotopeListRows = new Vector<MasterIsotopeListRow>();
		//Vector<Hashtable<Integer, IsotopePattern>> alignmentRows = new Vector<Hashtable<Integer, IsotopePattern>>();


		// Match eack isotope list against master isotope list
		// ---------------------------------------------------

		// Loop through all isotope lists
		rawDataIDEnum = isotopeLists.keys();
		int numberOfLists = isotopeLists.size();
		int currentList = 0;
		while (rawDataIDEnum.hasMoreElements()) {
			Integer rawDataID = rawDataIDEnum.nextElement();

			nodeServer.updateJobCompletionRate((double)(currentList)/(double)(numberOfLists));
			currentList++;



			// Calculate scores between isotopes in this list and rows currently in the master peak list
			// -----------------------------------------------------------------------------------------

			// Pickup isotope list for this rawDataID
			Hashtable<Integer, IsotopePattern> isotopeList = isotopeLists.get(rawDataID);

			// Calculate scores between all isotope patterns on the current list and all rows of the master isotope list
			TreeSet<PatternVsRowScore> scoreTree = new TreeSet<PatternVsRowScore>(new ScoreOrderer());

			// Loop isotope patterns
			Enumeration<IsotopePattern> isotopePatternEnum = isotopeList.elements();
			while (isotopePatternEnum.hasMoreElements()) {
				IsotopePattern isotopePattern = isotopePatternEnum.nextElement();

				// Loop master isotope list rows
				for (MasterIsotopeListRow masterIsotopeListRow : masterIsotopeListRows) {

					// Calc & store score
					PatternVsRowScore score = calculateScoreBetweenRowAndPattern(masterIsotopeListRow, isotopePattern, parameters);
					if (score.isGoodEnough()) scoreTree.add(score);

				}

			}

			// Browse scores in order of descending goodness-of-fit
			// ----------------------------------------------------

			Iterator<PatternVsRowScore> scoreIter = scoreTree.iterator();
			while (scoreIter.hasNext()) {
				PatternVsRowScore score = scoreIter.next();

				MasterIsotopeListRow masterIsotopeListRow = score.getMasterIsotopeListRow();
				IsotopePattern isotopePattern = score.getIsotopePattern();

				// Check if master list row is already assigned with an isotope pattern (from this rawDataID)
				if (masterIsotopeListRow.isAlreadyJoined()) { continue; }

				// Check if isotope pattern is already assigned to some master isotope list row
				if (isotopePattern.isAlreadyJoined()) { continue; }

				// Check if score good enough
				//if (score.isGoodEnough()) {
					// Assign isotope pattern to master peak list row
					masterIsotopeListRow.put(rawDataID, isotopePattern);

					// Mark pattern and isotope pattern row as joined
					masterIsotopeListRow.setJoined(true);
					isotopePattern.setJoined(true);
				//}

			}


			// Append all non-assigned isotope patterns to new rows of the master isotope list
			// -------------------------------------------------------------------------------

			isotopePatternEnum = isotopeList.elements();
			while (isotopePatternEnum.hasMoreElements()) {
				IsotopePattern isotopePattern = isotopePatternEnum.nextElement();
				if (!isotopePattern.isAlreadyJoined()) {
					MasterIsotopeListRow masterIsotopeListRow = new MasterIsotopeListRow();
					masterIsotopeListRow.put(new Integer(isotopePattern.getRawDataID()), isotopePattern);
					masterIsotopeListRows.add(masterIsotopeListRow);
				}
			}

			// Clear "Joined" information from all master isotope list rows
			// ------------------------------------------------------------
			for (MasterIsotopeListRow masterIsotopeListRow : masterIsotopeListRows) {
				masterIsotopeListRow.setJoined(false);
			}


		}

		// Convert master isotope list to master peak list
		// -----------------------------------------------

		// Get number of peak rows
		int numberOfRows = 0;
		for (MasterIsotopeListRow masterIsotopeListRow : masterIsotopeListRows) { numberOfRows += masterIsotopeListRow.getCombinedPatternSize(); }


		Vector<Integer> rawDataIDs = new Vector<Integer>();

		// Allocate arrays for storing common information (shared between all raw data)
		boolean[] commonStandardCompounds = new boolean[numberOfRows];
		int[] commonIsotopePatternIDs = new int[numberOfRows];
		int[] commonIsotopePeakNumbers = new int[numberOfRows];
		int[] commonChargeStates = new int[numberOfRows];

		// Allocate Hashtables for storing column arrays for each raw data
		Hashtable<Integer, int[]> peakStatuses = new Hashtable<Integer, int[]>();
		Hashtable<Integer, int[]> peakIDs = new Hashtable<Integer, int[]>();
		Hashtable<Integer, double[]> peakMZs = new Hashtable<Integer, double[]>();
		Hashtable<Integer, double[]> peakRTs = new Hashtable<Integer, double[]>();
		Hashtable<Integer, double[]> peakHeights = new Hashtable<Integer, double[]>();
		Hashtable<Integer, double[]> peakAreas = new Hashtable<Integer, double[]>();

		// Initialize Hashtables
		rawDataIDEnum = isotopeLists.keys();
		while (rawDataIDEnum.hasMoreElements()) {
			Integer rawDataID = rawDataIDEnum.nextElement();
			rawDataIDs.add(rawDataID);

			peakStatuses.put(rawDataID, new int[numberOfRows]);
			peakIDs.put(rawDataID, new int[numberOfRows]);
			peakMZs.put(rawDataID, new double[numberOfRows]);
			peakRTs.put(rawDataID, new double[numberOfRows]);
			peakHeights.put(rawDataID, new double[numberOfRows]);
			peakAreas.put(rawDataID, new double[numberOfRows]);
		}


		// Loop through master isotope list, and fill rows of master peak list
		int currentPeakListRow = 0;
		int currentIsotopePattern = 0;
		for (MasterIsotopeListRow masterIsotopeListRow : masterIsotopeListRows) {

			currentIsotopePattern++;

			// Loop through all different isotopic peaks available on this row
			int[] isotopePeakNumbers = masterIsotopeListRow.getCombinedPeakNumbers();
			for (int isotopePeakNumber : isotopePeakNumbers) {




				// Finally, loop through all raw data IDs participating in this alignment
				rawDataIDEnum = isotopeLists.keys();
				while (rawDataIDEnum.hasMoreElements()) {
					Integer rawDataID = rawDataIDEnum.nextElement();


					// Check if this raw data ID has isotope pattern on this row, and if this pattern contains corresponding isotope peak
					IsotopePattern isotopePattern = null;
					Peak p = null;
					isotopePattern = masterIsotopeListRow.get(rawDataID);
					if (isotopePattern!=null) { p = isotopePattern.getPeak(isotopePeakNumber); }
					if (p!=null) {

						// Isotope Pattern ID is given as a running numbering
						commonIsotopePatternIDs[currentPeakListRow] = currentIsotopePattern;

						// Isotope peak number and charge state must match between all peaks
						commonIsotopePeakNumbers[currentPeakListRow] = p.getIsotopePeakNumber();
						commonChargeStates[currentPeakListRow] = p.getChargeState();


						// Yes => Assign peak's information to current row and raw data's column
						(peakStatuses.get(rawDataID))[currentPeakListRow] = AlignmentResult.PEAKSTATUS_DETECTED;

						(peakIDs.get(rawDataID))[currentPeakListRow] = p.getPeakID();
						(peakMZs.get(rawDataID))[currentPeakListRow] = p.getMZ();
						(peakRTs.get(rawDataID))[currentPeakListRow] = p.getRT();
						(peakHeights.get(rawDataID))[currentPeakListRow] = p.getHeight();
						(peakAreas.get(rawDataID))[currentPeakListRow] = p.getArea();

					} else {

						// No. either the whole isotope pattern is missing, or this particular peak in the pattern was not detected in raw data file
						// Put missing information to current row and raw data's column
						(peakStatuses.get(rawDataID))[currentPeakListRow] = AlignmentResult.PEAKSTATUS_NOTFOUND;

						(peakIDs.get(rawDataID))[currentPeakListRow] = -1;
						(peakMZs.get(rawDataID))[currentPeakListRow] = -1;
						(peakRTs.get(rawDataID))[currentPeakListRow] = -1;
						(peakHeights.get(rawDataID))[currentPeakListRow] = -1;
						(peakAreas.get(rawDataID))[currentPeakListRow] = -1;

					}

				}

				// Move to next row
				currentPeakListRow++;

			}

		}


		AlignmentResult ar = new AlignmentResult(	rawDataIDs,
													commonStandardCompounds,
													commonIsotopePatternIDs,
													commonIsotopePeakNumbers,
													commonChargeStates,
													peakStatuses,
													peakIDs,
													peakMZs,
													peakRTs,
													peakHeights,
													peakAreas,
													new String("Raw peak data after alignment"));


		return ar;

    }



	/**
	 * Calculates score between two isotope patterns.
	 * Score defines how well these two isotope patterns match.
	 * Score==0 =>Not possible match
	 * Score>0  =>Possible match
	 * S
	 */
	private double calculateScoreBetweenPatterns(IsotopePattern pattern1, IsotopePattern pattern2, JoinAlignerParameters parameters) {


		// Scoring criteria
		// 1. charge state must be same
		// 2. number of matching peaks between patterns
		// 3. average difference between peaks in patterns
		// 4. number of peaks in patterns total

		// 1. compare charge states
		if (pattern1.getChargeState()!=pattern2.getChargeState()) { return 0; }


		// 2. number of matching peaks between patterns
		// 3. average difference between peaks in patterns
		int numberOfMatchingPeaks = 0;
		int numberOfNonMatchingPeaks = 0;
		double sumOfDifferences=0;
		double maxDifference=0;
		Enumeration<Integer> pattern1PeakNumberEnum = pattern1.getPeaks().keys();
		Enumeration<Peak> pattern1PeakEnum = pattern1.getPeaks().elements();
		Hashtable<Integer, Peak> pattern2PeakHash = pattern2.getPeaks();



		while (pattern1PeakNumberEnum.hasMoreElements()) {

			Integer pattern1PeakNumber = pattern1PeakNumberEnum.nextElement();
			Peak pattern1Peak = pattern1PeakEnum.nextElement();

			// Check if pattern 2 has same peak
			Peak pattern2Peak = pattern2PeakHash .get(pattern1PeakNumber);
			if (pattern2Peak!=null) {
				// Check if peaks from pattern 1 & 2 match within tolerances
				double pattern1MZ = pattern1Peak.getMZ();
				double pattern1RT = pattern1Peak.getRT();

				double pattern2MZ = pattern2Peak.getMZ();
				double pattern2RT = pattern2Peak.getRT();

				double rtTolerance = 0;
				if (parameters.paramRTToleranceUseAbs) {
					rtTolerance = parameters.paramRTToleranceAbs;
				} else {
					rtTolerance = parameters.paramRTTolerancePercent * 0.5 * (pattern1RT+pattern2RT);
				}

				if (	(java.lang.Math.abs(pattern1MZ-pattern2MZ)>parameters.paramMZTolerance) ||
						(java.lang.Math.abs(pattern1RT-pattern2RT)>rtTolerance)	) {
					numberOfNonMatchingPeaks++;
				} else { numberOfMatchingPeaks++; }

				sumOfDifferences += parameters.paramMZvsRTBalance * java.lang.Math.abs(pattern1MZ-pattern2MZ) + java.lang.Math.abs(pattern1RT-pattern2RT);
				double tmpDifference = parameters.paramMZvsRTBalance * parameters.paramMZTolerance + rtTolerance;
				if (maxDifference<tmpDifference) { maxDifference = tmpDifference; }

			}
		}
		// If most of the peak pairs between patterns don't match within tolerances,
		// then return 0 score for this pair
		if (numberOfNonMatchingPeaks>numberOfMatchingPeaks) { return 0; }

		double avgDifference = sumOfDifferences / (double)(numberOfMatchingPeaks+numberOfNonMatchingPeaks);

		double score = 10 * numberOfMatchingPeaks + (1 - avgDifference/maxDifference) + (pattern1.getPeaks().size()+pattern2.getPeaks().size()) / 1000.0;

		return score;

	}


	/**
	 * This function calculates score between isotope pattern and master isotope list row
	 * Calculation is based on scoring function between two isotope patterns.
	 */
	private PatternVsRowScore calculateScoreBetweenRowAndPattern(MasterIsotopeListRow masterIsotopeListRow, IsotopePattern isotopePattern, JoinAlignerParameters parameters) {
		Enumeration<IsotopePattern> patternsOnRowEnum = masterIsotopeListRow.elements();
		double[] scores = new double[masterIsotopeListRow.size()];

		int scoreIndex = 0;
		while (patternsOnRowEnum.hasMoreElements()) {
			IsotopePattern patternFromMaster = patternsOnRowEnum.nextElement();
			scores[scoreIndex] = calculateScoreBetweenPatterns(patternFromMaster, isotopePattern, parameters);
			scoreIndex++;
		}

		return new PatternVsRowScore(masterIsotopeListRow, isotopePattern, scores);

	}




	/**
	 * This class represents a score between master peak list row and isotope pattern
	 */
	private class PatternVsRowScore {

		MasterIsotopeListRow masterIsotopeListRow;
		IsotopePattern isotopePattern;
		double[] scores;

		double avgScore;
		int numOfOKScores;
		int numOfBadScores;

		public PatternVsRowScore(MasterIsotopeListRow _masterIsotopeListRow, IsotopePattern _isotopePattern, double[] _scores) {

			masterIsotopeListRow = _masterIsotopeListRow;
			isotopePattern = _isotopePattern;
			scores = _scores;

			preprocessScores();

		}

		public MasterIsotopeListRow getMasterIsotopeListRow() { return masterIsotopeListRow; }
		public IsotopePattern getIsotopePattern() { return isotopePattern; }

		public double getAverageScore() { return avgScore; }
		public int getNumberOfOKScores() { return numOfOKScores; }

		public boolean isGoodEnough() {
			if (numOfOKScores>=numOfBadScores) { return true; } else { return false; }
		}

		private void preprocessScores() {
			numOfOKScores = 0;
			numOfBadScores = 0;
			avgScore = 0;

			if ((scores==null) || (scores.length==0)) {	return;	}

			double sumOfScores=0;
			for (int scoreIndex=0; scoreIndex<scores.length; scoreIndex++) {
				if (scores[scoreIndex]>0) { numOfOKScores++; } else { numOfBadScores++; }
				sumOfScores+=scores[scoreIndex];
			}
			avgScore = sumOfScores / (double)scores.length;
		}


	}

	/**
	 * This is a helper class required for TreeSet to sorting scores in order of descending goodness of fit.
	 */
	private class ScoreOrderer implements Comparator<PatternVsRowScore> {
		public int compare(PatternVsRowScore score1, PatternVsRowScore score2) {

			// Pattern with more OK scores is better, and should come first
			if (score1.getNumberOfOKScores()>score2.getNumberOfOKScores()) { return -1; }
			if (score1.getNumberOfOKScores()<score2.getNumberOfOKScores()) { return 1; }

			// If both have equal number of OK scores, then the one with bigger average score is better
			// (if also average scores are equal, then simply give priority to score1)
			if (score1.getAverageScore()>=score2.getAverageScore()) { return -1; }

			return 1;

		}

		public boolean equals(Object obj) { return false; }
	}

	/**
	 * This class represent one row of the master isotope list
	 */
	private class MasterIsotopeListRow extends Hashtable<Integer, IsotopePattern> {
		private boolean alreadyJoined = false;

		public void setJoined(boolean b) { alreadyJoined = b; }
		public boolean isAlreadyJoined() { return alreadyJoined; }

		/**
		 * This function calculates the total number of different isotopic peaks in all patterns
		 */
		public int getCombinedPatternSize() {

			return getCombinedPeakNumbers().length;

		}

		/**
		 * This function collects all unique isotopic peak numbers participating in patterns on this row
		 */
		public int[] getCombinedPeakNumbers() {
			// Collect all unique isotope pattern peak numbers to this set
			HashSet<Integer> allPeakNumbers = new HashSet<Integer>();

			// Loop through all isotope patterns
			Enumeration<IsotopePattern> isotopePatternEnum = elements();
			while (isotopePatternEnum.hasMoreElements()) {
				IsotopePattern isotopePattern = isotopePatternEnum.nextElement();

				// Get isotope peak numbers of this pattern & and store them (if not already stored)
				Set<Integer> isotopePatternPeakNumbers = isotopePattern.getPeaks().keySet();
				allPeakNumbers.addAll(isotopePatternPeakNumbers);
			}

			int[] allPeakNumbersIntArray = new int[allPeakNumbers.size()];
			Iterator<Integer> allPeakNumbersIter = allPeakNumbers.iterator();
			int index=0;
			while (allPeakNumbersIter.hasNext()) {
				Integer peakNumber = allPeakNumbersIter.next();
				allPeakNumbersIntArray[index] = peakNumber.intValue();
				index++;
			}

			return allPeakNumbersIntArray;

		}


	}


	/**
	 * This class is used for keeping all peaks of a pattern together during alignment
	 */
	private class IsotopePattern {

		private int isotopePatternID;
		private int rawDataID;						// Raw data ID whose peaks are in this pattern

		private Hashtable<Integer, Peak> peaks;		// All peaks in this isotope pattern

		// These three values are picked up from the monoisotopic peak, so accessing them is fast
		private double monoisotopicMZ;
		private double monoisotopicRT;
		private int chargeState;

		private boolean alreadyJoined = false;



		public IsotopePattern(int _rawDataID) {
			rawDataID = _rawDataID;
			peaks = new Hashtable<Integer, Peak>();
		}

		public int getRawDataID() { return rawDataID; }

		public void addPeak(Peak p) {
			if (p.getIsotopePeakNumber()==0) {
				monoisotopicMZ = p.getMZ();
				monoisotopicRT = p.getRT();
				chargeState = p.getChargeState();
			}
			peaks.put(new Integer(p.getIsotopePeakNumber()), p);
		}

		public boolean containsPeakNumber(int peakNumber) {
			if (peaks.get(new Integer(peakNumber))!=null) { return true; } else { return false; }
		}

		public Peak getPeak(int peakNumber) {
			return peaks.get(new Integer(peakNumber));
		}

		public Hashtable<Integer, Peak> getPeaks() { return peaks; }
		public double getMonoisotopicMZ() {	return monoisotopicMZ; }
		public double getMonoisotopicRT() { return monoisotopicRT; }
		private int getChargeState() { return chargeState; }

		public boolean isAlreadyJoined() { return alreadyJoined; }
		public void setJoined(boolean b) { alreadyJoined = b; }


	}



	/**
	 * Customized parameter setup dialog for join aligner
	 */
	private class JoinAlignerParameterSetupDialog extends JDialog implements ActionListener {

		// Array for Text fields
		private JFormattedTextField txtMZvsRTBalance;
		private JFormattedTextField txtMZTolerance;
		private JComboBox cmbRTToleranceType;
		private JFormattedTextField txtRTToleranceAbsValue;
		private JFormattedTextField txtRTTolerancePercent;

		// Number formatting used in text fields
		private NumberFormat decimalNumberFormatOther;
		private NumberFormat decimalNumberFormatMZ;
		private NumberFormat percentFormat;

		// Options available in cmbRTToleranceType
		private Vector<String> optionsIncmbRTToleranceType;

		// Labels
		private JLabel lblMZvsRTBalance;
		private JLabel lblMZTolerance;
		private JLabel lblRTToleranceType;
		private JLabel lblRTToleranceAbsValue;
		private JLabel lblRTTolerancePercent;

		// Buttons
		private JButton btnOK;
		private JButton btnCancel;

		// Panels for all above
		private JPanel pnlAll;
		private JPanel pnlLabels;
		private JPanel pnlFields;
		private JPanel pnlButtons;

		// Parameter values
		JoinAlignerParameters params;

		// Exit code for controlling ok/cancel response
		private int exitCode = -1;


		/**
		 * Constructor
		 */
		public JoinAlignerParameterSetupDialog(MainWindow _mainWin, String title, JoinAlignerParameters _params) {
			super(_mainWin, title, true);

			params = _params;
			exitCode = -1;

			// Panel where everything is collected
			pnlAll = new JPanel(new BorderLayout());
			pnlAll.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
			getContentPane().add(pnlAll);

			// Two more panels: one for labels and another for text fields
			pnlLabels = new JPanel(new GridLayout(0,1));
			pnlFields = new JPanel(new GridLayout(0,1));

			// Setup number formats for text fields
			decimalNumberFormatMZ = NumberFormat.getNumberInstance();
			decimalNumberFormatMZ.setMinimumFractionDigits(3);
			decimalNumberFormatOther = NumberFormat.getNumberInstance();
			decimalNumberFormatOther.setMinimumFractionDigits(1);
			percentFormat = NumberFormat.getPercentInstance();

			// Create fields
			txtMZvsRTBalance = new JFormattedTextField(decimalNumberFormatOther);
			txtMZvsRTBalance.setColumns(8);
			txtMZvsRTBalance.setValue(params.paramMZvsRTBalance);
			pnlFields.add(txtMZvsRTBalance);

			txtMZTolerance = new JFormattedTextField(decimalNumberFormatMZ);
			txtMZTolerance.setColumns(8);
			txtMZTolerance.setValue(params.paramMZTolerance);
			pnlFields.add(txtMZTolerance);

			optionsIncmbRTToleranceType = new Vector<String>();
			optionsIncmbRTToleranceType.add(new String("Absolute (seconds)"));
			optionsIncmbRTToleranceType.add(new String("Percent of RT"));
			cmbRTToleranceType = new JComboBox(optionsIncmbRTToleranceType);
			cmbRTToleranceType.addActionListener(this);
			pnlFields.add(cmbRTToleranceType);

			txtRTToleranceAbsValue = new JFormattedTextField(decimalNumberFormatOther);
			txtRTToleranceAbsValue.setColumns(8);
			txtRTToleranceAbsValue.setValue(params.paramRTToleranceAbs);
			pnlFields.add(txtRTToleranceAbsValue);

			txtRTTolerancePercent = new JFormattedTextField(percentFormat);
			txtRTTolerancePercent.setColumns(8);
			txtRTTolerancePercent.setValue(params.paramRTTolerancePercent);
			pnlFields.add(txtRTTolerancePercent);



			// Create labels
			lblMZvsRTBalance = new JLabel("Balance between M/Z and RT");
			lblMZvsRTBalance.setLabelFor(txtMZvsRTBalance);
			pnlLabels.add(lblMZvsRTBalance);

			lblMZTolerance = new JLabel("M/Z tolerance size (Da)");
			lblMZTolerance.setLabelFor(txtMZTolerance);
			pnlLabels.add(lblMZTolerance);

			lblRTToleranceType = new JLabel("RT tolerance type");
			lblRTToleranceType.setLabelFor(cmbRTToleranceType);
			pnlLabels.add(lblRTToleranceType);

			lblRTToleranceAbsValue = new JLabel("RT tolerance size (seconds)");
			lblRTToleranceAbsValue.setLabelFor(txtRTToleranceAbsValue);
			pnlLabels.add(lblRTToleranceAbsValue);

			lblRTTolerancePercent = new JLabel("RT tolerance size (%)");
			lblRTTolerancePercent.setLabelFor(txtRTTolerancePercent);
			pnlLabels.add(lblRTTolerancePercent);


			if (params.paramRTToleranceUseAbs) {
				cmbRTToleranceType.setSelectedIndex(0);
			} else {
				cmbRTToleranceType.setSelectedIndex(1);
			}

			// Buttons
			pnlButtons = new JPanel();
			btnOK = new JButton("OK");
			btnOK.addActionListener(this);
			btnCancel = new JButton("Cancel");
			btnCancel.addActionListener(this);
			pnlButtons.add(btnOK);
			pnlButtons.add(btnCancel);

			pnlAll.add(pnlLabels,BorderLayout.CENTER);
			pnlAll.add(pnlFields,BorderLayout.LINE_END);
			pnlAll.add(pnlButtons,BorderLayout.SOUTH);

			getContentPane().add(pnlAll);

			setLocationRelativeTo(_mainWin);

			pack();


		}

		/**
		 * Implementation for ActionListener interface
		 */
		public void actionPerformed(java.awt.event.ActionEvent ae) {
			Object src = ae.getSource();
			if (src==btnOK) {

				// Copy values back to parameters object
				params.paramMZvsRTBalance = ((Number)(txtMZvsRTBalance.getValue())).doubleValue();
				params.paramMZTolerance = ((Number)(txtMZTolerance.getValue())).doubleValue();
				params.paramRTToleranceAbs = ((Number)(txtRTToleranceAbsValue.getValue())).doubleValue();
				params.paramRTTolerancePercent = ((Number)(txtRTTolerancePercent.getValue())).doubleValue();
				int ind = cmbRTToleranceType.getSelectedIndex();
				if (ind==0) {
					params.paramRTToleranceUseAbs = true;
				} else {
					params.paramRTToleranceUseAbs = false;
				}

				// Set exit code and fade away
				exitCode = 1;
				setVisible(false);
			}

			if (src==btnCancel) {
				exitCode = -1;
				setVisible(false);
			}

			if (src==cmbRTToleranceType) {
				int ind = cmbRTToleranceType.getSelectedIndex();
				if (ind==0) {
					// "Absolute" selected
					txtRTToleranceAbsValue.setEnabled(true);
					lblRTToleranceAbsValue.setEnabled(true);

					txtRTTolerancePercent.setEnabled(false);
					lblRTTolerancePercent.setEnabled(false);
				}

				if (ind==1) {
					// "Percent" selected
					txtRTToleranceAbsValue.setEnabled(false);
					lblRTToleranceAbsValue.setEnabled(false);

					txtRTTolerancePercent.setEnabled(true);
					lblRTTolerancePercent.setEnabled(true);
				}

			}

		}

		/**
		 * Method for reading contents of a field
		 * @param	fieldNum	Number of field
		 * @return	Value of the field
		 */
		public JoinAlignerParameters getParameters() {
			return params;
		}

		/**
		 * Method for reading exit code
		 * @return	1=OK clicked, -1=cancel clicked
		 */
		public int getExitCode() {
			return exitCode;
		}

	}

}
