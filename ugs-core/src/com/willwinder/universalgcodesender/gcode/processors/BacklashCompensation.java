package com.willwinder.universalgcodesender.gcode.processors;

import com.google.common.collect.Iterables;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodePreprocessorUtils;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserUtils;
import com.willwinder.universalgcodesender.gcode.util.PlaneFormatter;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.types.PointSegment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class BacklashCompensation implements CommandProcessor {
    // Compensation values for X, Y, and Z axes
    private final double xComp;
    private final double yComp;
    //private final double zComp;

    // Variables that keep track of the last movement direction of the X, Y and Z axes
    private static int lastXMove = 1;
    private static int lastYMove = 1;
    private static int lastZMove = 1;

    // Contains G0, G1, G2, or G3 not followed by another digit
    private Pattern g0123Pattern = Pattern.compile(".*[gG][0123](?!\\d)(\\D.*)?");
    // Contains a coordinate but uses previous command
    private Pattern xyzPattern = Pattern.compile(".*[xXyYzZ](?!\\d)(\\D.*)?");
    // Latest command, used if given coordinates without new command
    private static String latestCommand = "G0";

    // Contains new commands needed to compensate for the received command
    private List<String> compCommands = new ArrayList<String>();

    @Override
    public String getHelp() {
        return "Provides backlash compensation in the X and Y axes.";
    }

    public BacklashCompensation(double xComp, double yComp) {
        this.xComp = Math.abs(xComp);
        this.yComp = Math.abs(yComp);
        //this.zComp = zComp;
    }

    @Override
    public List<String> processCommand(String command, GcodeState state) throws GcodeParserException {
        String noComments = GcodePreprocessorUtils.removeComment(command);
        List<String> commandWords = GcodePreprocessorUtils.splitCommand(noComments);

        // Update lastestCommand variable.
        if (Character.toUpperCase(commandWords.get(0).charAt(0)) == 'G') {
            latestCommand = commandWords.get(0);
        }

        if (g0123Pattern.matcher(noComments).matches()) {
            for (String t : commandWords) {
                char c = Character.toUpperCase(t.charAt(0));
                if (c == 'G') {
                    latestCommand = t;
                } else if (c == 'X') {

                } else if (c == 'Y') {

                }
            }
            //return Arrays.asList(command, dwellCommand);
        } else if (xyzPattern.matcher(noComments).matches() && g0123Pattern.matcher(latestCommand).matches()) {

        }
        return Collections.singletonList(command);
    }

    private List<String> compensateArcQuadrants(String command, GcodeState state) throws GcodeParserException {
        List<String> commandWords = GcodePreprocessorUtils.splitCommand(command);

        // Center of Rotation
        double xCOR;
        double yCOR;
        // Arc end coordinates
        double xEnd;
        double yEnd;
        // CoR offset values
        double iOffset = 0;
        double jOffset = 0;

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


        return Collections.singletonList(command);
    }

}
