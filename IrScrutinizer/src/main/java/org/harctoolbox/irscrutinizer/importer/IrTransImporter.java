/*
Copyright (C) 2014 Bengt Martensson.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or (at
your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see http://www.gnu.org/licenses/.
*/

package org.harctoolbox.irscrutinizer.importer;

import java.io.File;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Reader;
import java.io.Serializable;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.harctoolbox.IrpMaster.IrSignal;
import org.harctoolbox.IrpMaster.IrpMasterException;
import org.harctoolbox.girr.Command;
import org.harctoolbox.girr.Remote;
import org.harctoolbox.girr.RemoteSet;
import org.harctoolbox.irscrutinizer.Version;

/**
 * This class imports IrTrans files.
 *
 * The format is sort-of documented at <a href="http://www.irtrans.de/download/Docs/2013/Softwarehandbuch_EN.pdf">http://www.irtrans.de/download/Docs/2013/Softwarehandbuch_EN.pdf</a>,
 * Section 7.5.
 * However, that description is incomplete.
 *
 */
public class IrTransImporter extends RemoteSetImporter implements IReaderImporter, Serializable {
    public static final String homeUrl = "http://www.irtrans.com";
    public static final String defaultCharsetName = "windows-1252";
    private static final int dummyEndingGap = 50000;

    public IrTransImporter() {
        super();
    }

    private static enum TimingType {
        rc5,
        rc6,
        normal
    }

    private static class Timing {

        int[][] durations = null;
        int repetitions = -1;
        int pause = -1;
        int framelength = -1;
        int frequency = -1;
        boolean freqMeas = false;
        boolean startBit = false;
        boolean repeatStart = false;
        TimingType type = TimingType.normal;
        boolean noToggle = false;
        boolean rcmmToggle = false;
    }

    private static enum CommandType {
        raw,
        ccf,
        timing
    }

    private static abstract class IrTransCommand {
        String name;

        private IrTransCommand() {}

        protected IrTransCommand(String name) {
            this.name = name;
        }

        abstract Command toCommand() throws IrpMasterException;
    }

    private static class IrTransCommandIndexed extends IrTransCommand {
        private final String data;
        private final Timing timing;

        IrTransCommandIndexed(String name, String data, Timing timing) {
            super(name);
            this.data = data;
            this.timing = timing;
        }

        @Override
        Command toCommand() throws IrpMasterException {
            if (timing.type == TimingType.rc5) {
                    // {36k,msb,889}<1,-1|-1,1>((1:1,~F:1:6,T:1,D:5,F:6,^114m)+,T=1-T)[T@:0..1=0,D:0..31,F:0..127]
                    long payload = Long.parseLong(data, 2);
                    long F6 = (~(payload >> 12)) & 1;
                    long F = (F6 << 6) | (payload & 0x3f);
                    long D = (payload >> 6) & 0x1f;
                    long T = (payload >> 11) & 1;
                    Map<String, Long> parameters = new HashMap<>(4);
                    parameters.put("F", F);
                    parameters.put("D", D);
                    parameters.put("T", T);
                    return new Command(name, null, "RC5", parameters);
            } else if (timing.type == TimingType.rc6) {
                    // {36k,444,msb}<-1,1|1,-1>((6,-2,1:1,0:3,<-2,2|2,-2>(T:1),D:8,F:8,^107m)+,T=1-T) [D:0..255,F:0..255,T@:0..1=0]
                    // http://www.irtrans.de/forum/viewtopic.php?f=18&t=99
                    long payload = Long.parseLong(data.substring(2), 2);
                    long F = payload & 0xff;
                    long D = (payload >> 8) & 0xff;
                    Map<String, Long> parameters = new HashMap<>(4);
                    parameters.put("F", F);
                    parameters.put("D", D);
                    return new Command(name, null, "RC6", parameters);
            } else {
                    int[] times = new int[2 * data.length()];
                    for (int i = 0; i < data.length(); i++) {
                        char ch = data.charAt(i);
                        int index = ch == 'S' ? 0 : (Character.digit(ch, Character.MAX_RADIX) + (timing.startBit ? 1 : 0));
                        if (index >= timing.durations.length)
                            throw new IrpMasterException("Undefined timing :" + ch);
                        times[2 * i] = timing.durations[index][0];
                        times[2 * i + 1] = timing.durations[index][1];
                    }
                    IrSignal irSignal = new IrSignal(times, 0, times.length / 2, 1000 * timing.frequency);
                    return new Command(name, null, irSignal);
            }
        }
    }

    private static class IrTransCommandRaw extends IrTransCommand {
        private final int[] durations;
        private final int frequency;

        IrTransCommandRaw(String name, int frequency, int[] durations, int effectiveLength) {
            super(name);
            this.frequency = frequency;
            this.durations = new int[effectiveLength];
            System.arraycopy(durations, 0, this.durations, 0, effectiveLength);
        }

        @Override
        Command toCommand() {
            IrSignal irSignal = new IrSignal(durations, 0, durations.length/2, 1000 * frequency);
            return new Command(name, null, irSignal);
        }
    }

    private static class IrTransCommandCcf extends IrTransCommand {
        String ccf;

        IrTransCommandCcf(String name, String ccf) {
            super(name);
            this.ccf = ccf;
        }

        @Override
        Command toCommand() throws IrpMasterException {
            return new Command(name, null, ccf);
        }
    }

    private Remote parseRemote(LineNumberReader reader) throws IOException, ParseException {
        String name = parseName(reader);
        if (name == null)
            return null; // EOF
        ArrayList<Timing> timings = parseTimings(reader);
        Map<String, IrTransCommand> parsedCommands = parseCommands(reader, timings);
        Map<String, Command> commands = new LinkedHashMap<>(4);
        for (IrTransCommand cmd : parsedCommands.values()) {
            try {
                Command command = cmd.toCommand();
                commands.put(command.getName(), command);
            } catch (IrpMasterException ex) {
                System.err.println(cmd.name + " Unparsable signal: " + ex.getMessage());
            }
        }
        return new Remote(new Remote.MetaData(name), null, null, commands, null);
    }

    private String parseName(LineNumberReader reader) throws IOException, ParseException {
        boolean success = gobbleTo(reader, "[REMOTE]", true);
        if (!success)
            return null;
        String line = reader.readLine();
        if (line == null)
            throw new ParseException("[NAME] not found.", reader.getLineNumber());
        String[] arr = line.trim().split("]");
        if (!arr[0].equals("[NAME"))
            throw new ParseException("[NAME] not found.", reader.getLineNumber());
        return arr[1];
    }

    private LinkedHashMap<String, IrTransCommand> parseCommands(LineNumberReader reader, ArrayList<Timing> timings) throws IOException, ParseException {
        gobbleTo(reader, "[COMMANDS]", true);
        LinkedHashMap<String, IrTransCommand> commands = new LinkedHashMap<>(32);
        while (true) {
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty())
                break;

            try {
                IrTransCommand command = parseCommand(line, timings, reader.getLineNumber());
                commands.put(command.name, command);
            } catch (ParseException | NumberFormatException ex) {
                // just ignore unparsable command, go on reading
                System.err.println("Command " + line + " (line " + reader.getLineNumber() + ") did not parse, ignored.");
            }
        }
        return commands;
    }

    private IrTransCommand parseCommand(String line, ArrayList<Timing> timings, int lineNo) throws ParseException {
        IrTransCommand command = null;
        String[] arr = line.trim().split("[\\[\\]]");
        int index = 1;
        String name = arr[index++];
        index++;
        String type = arr[index++];
        switch (type) {
            case "RAW": {
                int noNumbers = Integer.parseInt(arr[index++]);
                if (!arr[index++].equals("FREQ"))
                    throw new ParseException("No FREQ in raw signal", lineNo);
                int frequency = Integer.parseInt(arr[index++]);
                if (!arr[index++].equals("D"))
                    throw new ParseException("[D] not found", lineNo);
                String data = arr[index++];
                String[] numbers = data.split(" ");
                if (numbers.length != noNumbers)
                    throw new ParseException("Wrong number of durations", lineNo);
                int[] times = new int[noNumbers + (noNumbers % 2)];
                int durationIndex = 0;
                int numberIndex = 0;
                while (numberIndex < noNumbers) {
                    int t = Integer.parseInt(numbers[numberIndex]);
                    if (t == 0) {
                        times[durationIndex] = 256*Integer.parseInt(numbers[numberIndex+1]) + Integer.parseInt(numbers[numberIndex+2]);
                        numberIndex += 3;
                    } else {
                        times[durationIndex] = t;
                        numberIndex++;
                    }
                    durationIndex++;
                }
                if ((durationIndex % 2) != 0)
                    times[durationIndex++] = dummyEndingGap;
                command = new IrTransCommandRaw(name, frequency, times, durationIndex);
                break;
            }

            case "CCF":
                command = new IrTransCommandCcf(name, arr[index++]);
                break;
            case "T": {
                int timingNo = Integer.parseInt(arr[index++]);
                if (!arr[index++].equals("D"))
                    throw new ParseException("[D] not found", lineNo);
                String data = arr[index++];
                command = new IrTransCommandIndexed(name, data, timings.get(timingNo));
                break;
            }
            default:
                throw new ParseException("Unknown command type " + type, lineNo);
        }

        if (index != arr.length)
            throw new ParseException("unparsable line", lineNo);
        return command;
    }

    private boolean gobbleTo(LineNumberReader reader, String target, boolean throwException) throws IOException, ParseException {
        String line = "";
        reader.mark(255);
        while (line.isEmpty()) {
            line = reader.readLine();
            if (line == null)
                return false;
        }
        if (!line.trim().equals(target)) {
            if (throwException)
                throw new ParseException(target + " not found.", reader.getLineNumber());
            else {
                reader.reset();
                return false;
            }
        }
        return true;
    }

    private ArrayList<Timing> parseTimings(LineNumberReader reader) throws IOException, ParseException {
        ArrayList<Timing> timings = new ArrayList<>(16);
        boolean hasTiming = gobbleTo(reader, "[TIMING]", false);
        if (!hasTiming)
            return null;

        while (true) {
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty())
                break;
            String[] arr = line.trim().split("[\\[\\]]");
            int number = Integer.parseInt(arr[1]);
            Timing timing = new Timing();
            timings.add(number, timing);
            int index = 3;
            //int[][] timings = null;
            while(index < arr.length) {
                String token = arr[index];
                if (token.isEmpty()) {
                    index++;
                    continue;
                }
                switch (token) {
                    case "N":
                        timing.durations = new int[Integer.parseInt(arr[++index])][2];
                        break;
                    case "RC":
                        timing.repetitions = Integer.parseInt(arr[++index]);
                        break;
                    case "RP":
                        timing.pause = Integer.parseInt(arr[++index]);
                        break;
                    case "FL":
                        timing.framelength = Integer.parseInt(arr[++index]);
                        timing.pause = -1;
                        break;
                    case "FREQ":
                        timing.frequency = Integer.parseInt(arr[++index]);
                        break;
                    case "FREQ-MEAS":
                        timing.freqMeas = true;
                        break;
                    case "SB":
                        timing.startBit = true;
                        break;
                    case "RS":
                        timing.repeatStart = true;
                        break;
                    case "RC5":
                        timing.type = TimingType.rc5;
                        break;
                    case "RC6":
                        timing.type = TimingType.rc6;
                        break;
                    case "NOTOG":
                        timing.noToggle = true;
                        break;
                    case "RCMM-TOGGLE":
                        timing.rcmmToggle = true;
                        break;
                    case "RO":
                        // treat as junk, but with one argument, see
                        // See http://www.irtrans.de/forum/viewtopic.php?f=24&t=3970
                        ++index;
                        break;
                    case "IRDA":
                    case "IRDA-RAW":
                        // treat as junk
                        break;
                    default:
                        try {
                            int timingNumber = Integer.parseInt(token);
                            String[] durations = arr[++index].split(" ");
                            for (int i = 0; i < 2; i++)
                                timing.durations[timingNumber-1][i] = Integer.parseInt(durations[i]);
                        } catch (NumberFormatException ex) {
                            throw new ParseException("Unknown token: " + token, reader.getLineNumber());
                        }
                        break;
                }
                index++;
            }
        }
        return timings;
    }

    @Override
    public void load(Reader reader, String origin) throws IOException, ParseException {
        prepareLoad(origin);
        LineNumberReader bufferedReader = new LineNumberReader(reader);
        Map<String, Remote> remotes = new HashMap<>(16);
        while (true) {
            Remote remote = parseRemote(bufferedReader);
            if (remote == null)
                break;
            remotes.put(remote.getName(), remote);
        }

        remoteSet = new RemoteSet(getCreatingUser(),
                origin, //java.lang.String source,
                (new Date()).toString(), //java.lang.String creationDate,
                Version.appName, //java.lang.String tool,
                Version.version, //java.lang.String toolVersion,
                null, //java.lang.String tool2,
                null, //java.lang.String tool2Version,
                null, //java.lang.String notes,
                remotes);
    }

    public void load(File file) throws IOException, ParseException {
        load(file, defaultCharsetName);
    }

    @Override
    public boolean canImportDirectories() {
        return false;
    }

    @Override
    public String[][] getFileExtensions() {
        return new String[][]{new String[]{"IrTrans files (*.rem)", "rem" }};
    }

    @Override
    public String getFormatName() {
        return "IrTrans";
    }

    public static void main(String[] args) {
        IrTransImporter importer = new IrTransImporter();
        try {
            importer.load(new File(args[0]));
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
        } catch (ParseException ex) {
            System.err.println(ex.getMessage() + ex.getErrorOffset());
        }
    }
}
