package com.willwinder.universalgcodesender.gcode.processors;

import com.google.common.collect.Iterables;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodePreprocessorUtils;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserUtils;
import com.willwinder.universalgcodesender.gcode.util.Plane;
import static com.willwinder.universalgcodesender.gcode.util.Plane.*;

import com.willwinder.universalgcodesender.gcode.util.PlaneFormatter;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.model.Position;
import static com.willwinder.universalgcodesender.model.UnitUtils.Units.MM;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.willwinder.universalgcodesender.types.PointSegment;
import org.junit.Test;
import static org.assertj.core.api.Assertions.*;
import org.assertj.core.data.Offset;
import org.junit.Assert;

/**
 *
 * @author wwinder
 */
public class BacklashCompensationTest {
    // Compensation values for X, Y, and Z axes
    private final double xComp = 0.3;
    private final double yComp = 0.1;
    //private final double zComp = 0;

    // Variables that keep track of the last movement direction of the X, Y and Z axes
    private static int lastXMove = -1;
    private static int lastYMove = -1;
    //private static int lastZMove = 1;

    // Global offsets used to shift all toolpaths post-compensation
    private static double xOffset = 0;
    private static double yOffset = 0;
    //private static double zOffset = 0;

    // Contains G0, G1, G2, or G3 not followed by another digit
    private Pattern g0123Pattern = Pattern.compile(".*[gG][0123](?!\\d)(\\D.*)?");
    // Contains a coordinate but uses previous command
    private Pattern xyzPattern = Pattern.compile(".*[xXyYzZ](?!\\d)(\\D.*)?");
    // Latest command, used if given coordinates without new command
    private static String latestCommand = "G0";

    // Contains new commands needed to compensate for the received command
    private List<String> compCommands = new ArrayList<String>();

    @Test
    public void splitGCodeTest() throws Exception {

        //String command = "G0X-10.784Y-9.821";
        String command = "G3 X4 Y3 I-3 J-4";
        GcodeState state = new GcodeState();
        state.currentPoint = new Position(3,4,0);
        List<String> ret = processCommand(command, state);
        for (String t : ret) {
            System.out.println(t);
        }

    }

    public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
        String noComments = GcodePreprocessorUtils.removeComment(command);
        List<String> commandWords = GcodePreprocessorUtils.splitCommand(noComments);

        // Update lastestCommand variable.
        if (Character.toUpperCase(commandWords.get(0).charAt(0)) == 'G') {
            latestCommand = commandWords.get(0);
        }

        if (latestCommand.equals("G0") || latestCommand.equals("G1")) {
            return compensateLine(noComments, state);
        }
        if (latestCommand.equals("G2") || latestCommand.equals("G3")) {
            return compensateArcQuadrants(noComments, state);
        }

        /*if (g0123Pattern.matcher(noComments).matches()) {
            for (String t : commandWords) {
                char c = Character.toUpperCase(t.charAt(0));
                if (c == 'X') {

                } else if (c == 'Y') {

                }
            }
            if (latestCommand.equals("G2")) {
                System.out.println(commandWords);
                compensateArcQuadrants(noComments, state);
            }
            //return Arrays.asList(command, dwellCommand);
        } else if (xyzPattern.matcher(noComments).matches() && g0123Pattern.matcher(latestCommand).matches()) {

        }*/

        return Collections.singletonList(command);
    }

    private List<String> compensateLine(String command, GcodeState state) {



        return Collections.singletonList(command);
    }

    private List<String> compensateArcQuadrants(String command, GcodeState state) throws GcodeParserException {
        List<String> commandWords = GcodePreprocessorUtils.splitCommand(command);

        // Center of Rotation
        double xCOR;
        double yCOR;
        // Arc end coordinates
        double xEnd = 0;
        double yEnd = 0;
        // CoR offset values
        double iOffset = 0;
        double jOffset = 0;
        // Arc Radius
        double radius = 0;
        // True if CounterClockwise (G3), False if Clockwise (G2)
        boolean isCCW = latestCommand.equals("G3");
        // Angles
        double startAngle;
        double endAngle;
        // New commands needed to replace original command
        List<String> newCommands= new ArrayList<String>();

        // Get info from state
        List<GcodeParser.GcodeMeta> commands = GcodeParserUtils.processCommand(command, 0, state);
        GcodeParser.GcodeMeta arcMeta = Iterables.getLast(commands);
        PointSegment ps = arcMeta.point;
        Position start = state.currentPoint;
        PlaneFormatter plane = new PlaneFormatter(ps.getPlaneState());
        double xStart = plane.axis0(start);
        double yStart = plane.axis1(start);

        // Get info from command
        for (String t : commandWords) {
            char c = Character.toUpperCase(t.charAt(0));
            if (c == 'X') {
                xEnd = Double.parseDouble(t.substring(1));
            } else if (c == 'Y') {
                yEnd = Double.parseDouble(t.substring(1));
            } else if (c == 'I') {
                iOffset = Double.parseDouble(t.substring(1));
            } else if (c == 'J') {
                jOffset = Double.parseDouble(t.substring(1));
            }
        }

        // Calculate Center of Rotation
        xCOR = xStart + iOffset;
        yCOR = yStart + jOffset;

        // Calculate Angles
        startAngle = calculateAngle(xCOR, yCOR, xStart, yStart);
        endAngle = calculateAngle(xCOR, yCOR, xEnd, yEnd);

        // Calculate Radius
        radius = Math.sqrt((xEnd - xCOR)*(xEnd - xCOR) + (yEnd - yCOR)*(yEnd - yCOR));

        if (isCCW) { // Is a G3 command (counterclockwise)
            // Correction for cases where endAngle is greater than startAngle
            if (startAngle >= endAngle) {
                endAngle += 2 * Math.PI;
            }

            // Used to keep track of where the loop is in the arc
            double currAngle = startAngle;
            // Used to make sure the first iteration of the loop starts at the arc start point instead of at a 90 degree interval
            boolean isFirstLoop = true;


            // Continue to create new arc commands until arcs stop crossing X and Y axes
            while (endAngle > currAngle) {
                if (getQuadrantCCW(currAngle) != getQuadrantCCW(endAngle) || (endAngle%(2 * Math.PI))<currAngle) {
                    int currQuadrant = getQuadrantCCW(currAngle);
                    String tempCommand = "G3";
                    if (currQuadrant == 0) { // Crossing positive Y axis -- Compensate Y direction
                        double[] newPoint = calculatePointFromAngle(Math.PI / 2, radius, xCOR, yCOR);
                        tempCommand += " X" + newPoint[0];
                        tempCommand += " Y" + newPoint[1];
                        if (isFirstLoop) {
                            tempCommand += " I" + (iOffset);
                            tempCommand += " J" + (jOffset);
                            isFirstLoop = false;
                        } else {
                            tempCommand += " I0";
                            tempCommand += " J" + (radius * -1);
                        }
                        // Add sliced arc
                        newCommands.add(tempCommand);
                        // Use G92 command to do global coordinate system offset to compensate for backlash on Y axis
                        newCommands.addAll(compensateCommand("Y", -1, newPoint[0], newPoint[1]));
                        // Set new currentAngle
                        currAngle = Math.PI / 2;
                    } else if (currQuadrant == 1) { // Crossing negative X axis -- Compensate X direction
                        double[] newPoint = calculatePointFromAngle(Math.PI, radius, xCOR, yCOR);
                        tempCommand += " X" + newPoint[0];
                        tempCommand += " Y" + newPoint[1];
                        if (isFirstLoop) {
                            tempCommand += " I" + (iOffset);
                            tempCommand += " J" + (jOffset);
                            isFirstLoop = false;
                        } else {
                            tempCommand += " I" + radius;
                            tempCommand += " J0";
                        }
                        // Add sliced arc
                        newCommands.add(tempCommand);
                        // Use G92 command to do global coordinate system offset to compensate for backlash on Y axis
                        newCommands.addAll(compensateCommand("X", 1, newPoint[0], newPoint[1]));
                        // Set new currentAngle
                        currAngle = Math.PI;
                    } else if (currQuadrant == 2) { // Crossing negative Y axis -- Compensate Y direction
                        double[] newPoint = calculatePointFromAngle(Math.PI * 3 / 2, radius, xCOR, yCOR);
                        tempCommand += " X" + newPoint[0];
                        tempCommand += " Y" + newPoint[1];
                        if (isFirstLoop) {
                            tempCommand += " I" + (iOffset);
                            tempCommand += " J" + (jOffset);
                            isFirstLoop = false;
                        } else {
                            tempCommand += " I0";
                            tempCommand += " J" + radius;
                        }
                        // Add sliced arc
                        newCommands.add(tempCommand);
                        // Use G92 command to do global coordinate system offset to compensate for backlash on Y axis
                        newCommands.addAll(compensateCommand("Y", 1, newPoint[0], newPoint[1]));
                        // Set new currentAngle
                        currAngle = Math.PI * 3 / 2;
                    } else { // Crossing positive X axis  -- Compensate X direction
                        double[] newPoint = calculatePointFromAngle(Math.PI * 2, radius, xCOR, yCOR);
                        tempCommand += " X" + newPoint[0];
                        tempCommand += " Y" + newPoint[1];
                        if (isFirstLoop) {
                            tempCommand += " I" + (iOffset);
                            tempCommand += " J" + (jOffset);
                            isFirstLoop = false;
                        } else {
                            tempCommand += " I" + (radius * -1);
                            tempCommand += " J0";
                        }
                        // Add sliced arc
                        newCommands.add(tempCommand);
                        // Use G92 command to do global coordinate system offset to compensate for backlash on Y axis
                        newCommands.addAll(compensateCommand("X", -1, newPoint[0], newPoint[1]));
                        // Set new currentAngle
                        currAngle = 0;
                    }
                } else {
                    // End of arc
                    String tempCommand = "G3 X" + xEnd + " Y" + yEnd;
                    int currQuad = getQuadrantCCW(endAngle);

                    if (currQuad == 0) {
                        tempCommand += " I" + (radius*-1) + " J0";
                    } else if (currQuad == 1) {
                        tempCommand += " I0 J" + (radius*-1);
                    } else if (currQuad == 2) {
                        tempCommand += " I" + radius + " J0";
                    } else {
                        tempCommand += " I0 J" + radius;
                    }

                    newCommands.add(tempCommand);
                    currAngle = endAngle;
                }
            }
        } else { // Is a G2 command (clockwise)
            // Probably need a second variant of the getQuadrant(currAngle) to shift range limits...
        }

        System.out.println("Center of Rotation: ["+xCOR+","+yCOR+"]");
        System.out.println("Start Point: ["+xStart+","+yStart+"]");
        System.out.println("End Point: ["+xEnd+","+yEnd+"]");
        System.out.println("Offsets: ["+iOffset+","+jOffset+"]");
        System.out.println("Radius: "+radius);
        System.out.println("Start Point Angle: "+startAngle);
        System.out.println("End Point Angle: "+endAngle);

        return newCommands;
    }

    private double calculateAngle(double xCOR, double yCOR, double xPoint, double yPoint) {
        double deltaX = xPoint - xCOR;
        double deltaY = yPoint - yCOR;
        return Math.atan2(deltaY, deltaX);
    }

    private int getQuadrant(double xCOR, double yCOR, double xPoint, double yPoint) {
        double angle = calculateAngle(xCOR, yCOR, xPoint, yPoint);
        if (angle >= 0 && angle < Math.PI/2) {
            return 0;
        } else if (angle >= Math.PI/2 && angle < Math.PI) {
            return 1;
        } else if (angle >= Math.PI && angle < Math.PI*3/2) {
            return 2;
        } else {
            return 3;
        }
    }

    private int getQuadrantCCW(double angle) {
        angle = angle % (Math.PI * 2);
        if (angle >= 0 && angle < Math.PI/2) {
            return 0;
        } else if (angle >= Math.PI/2 && angle < Math.PI) {
            return 1;
        } else if (angle >= Math.PI && angle < Math.PI*3/2) {
            return 2;
        } else {
            return 3;
        }
    }

    private int getQuadrantCW(double angle) {
        angle = angle % (Math.PI * 2);
        if (angle >= 0 && angle < Math.PI/2) {
            return 0;
        } else if (angle >= Math.PI/2 && angle < Math.PI) {
            return 1;
        } else if (angle >= Math.PI && angle < Math.PI*3/2) {
            return 2;
        } else {
            return 3;
        }
    }

    private double[] calculatePointFromAngle(double angle, double radius, double xCOR, double yCOR) {
        double[] ret = new double[2];
        DecimalFormat df = new DecimalFormat("#.####");
        df.setRoundingMode(RoundingMode.CEILING);
        ret[0] = Double.parseDouble(df.format(Math.cos(angle)*radius + xCOR));
        ret[1] = Double.parseDouble(df.format(Math.sin(angle)*radius + yCOR));
        return ret;
    }

    private List<String> compensateCommand(String axis, int direction, double xEnd, double yEnd) {
        if (axis.toUpperCase(Locale.ROOT).equals("X")) {
            if (lastXMove == -1 && direction == 1) {
                lastXMove = 1;
                xOffset = xComp;
            } else if (lastXMove == 1 && direction == -1) {
                lastXMove = -1;
                xOffset = 0;
            }
        } else if (axis.toUpperCase(Locale.ROOT).equals("Y")) {
            if (lastYMove == -1 && direction == 1) {
                lastYMove = 1;
                yOffset = yComp;
            } else if (lastYMove == 1 && direction == -1) {
                lastYMove = -1;
                yOffset = 0;
            }
        }

        ArrayList<String> ret = new ArrayList<String>();

        ret.add("G92 X" + xOffset + " Y" + yOffset);
        ret.add("G1 X" + xEnd + " Y" + yEnd);
        // Need G1 command to move to offset end point (axes are shifted, but current position is now (-xComp) out of position

        return ret;
    }

}

