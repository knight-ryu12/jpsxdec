/* 
 * jPSXdec: Playstation 1 Media Decoder/Converter in Java
 * Copyright (C) 2007  Michael Sabin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

/*
 * Main.java
 *
 */

/* TODO:
 * - finish STR format documentation
 * - make code documentation
 * - CLEANUP!!
 * - get ready to package release into exe file
 * - better organize the error reporting/checking
 * - develop a test set
 * - figure out IDCT license
 * - Add check to output if input file is ISO style
 * - Add Settings check for negitive arguments
 * - add sector finder walker FindNextMatchingSector()
 * - somehow unify CDIndexer and the later FindFrameSectors/FindAudioSectors
 * -add syncronized to CD sector reading
 * - get rid of CDSectorIterator and replace with PSXSectorIterator
 *
 * FUTURE VERSIONS:
 * - Add frame rate calculation
 * - CDSectorReader will extend AbstractList<CDSector>
 *   So we can use listIterator(). The advantage of using Iterator
 *   is that I can pass the iterator into the CDMedia classes as they step
 *   through the sectors to identify them and record that they find.
 *   That's basically what I'm doing already anyway. Should probably formalize it.
 *   ListIterator also allows starting at a certain point in the list,
 *   and even moving backwards (don't think that will be used, though).
 *   Using iterators also allows the class to be thread safe
 *   (be sure to use synchronized for the underlying seeking and reading
 *   functions)
 * - CDSectorReader extending AbstractList<CDSector> will also provide
 *   get(#) function to return a specific sector number.
 * - CDSector class. When the class is created, it reads the sector
 *   to get the sector header info, and KEEP the sector in memory. It will
 *   have a getSectorData() to return the userdata portion of the sector.
 * - SectorIdentifyFactory will accept one of these CDSector classes,
 *   and keep creating ByteArrayInputStreams to read the sector data.
 *   PsxSectorAbstract will not keep the CDSector instance, but will
 *   copy the information it wants, most importantly, the sector number.
 * - PsxSectorAbstract.buffer() will call CDSectorReader.get() to once again 
 *   get the sector, then call CDSector.getSectorData() to get the user-data
 *   of the sector, and then free the CDSector again. read() will then
 *   step through the demuxed bytes. Finally, PsxSectorAbstract.release() will  
 *   release the bytes.
 * - add better stdin/stdout file handling
 * - add FF8 audio decoding
 * - add super fast IDCT from that java mpeg1 decoder
 * - add GUI
 * - add raw CD reading
 * - add encoding to a video format
 * - add --nocrop option
 * - add option to save audio to the other formats
 * 
 * CONSIDER:
 * - moving NotThisTypeException into utils
 * - moving the IDCT class into its own file, and making each IDCT a separate sub-class
 * - consider making a CDMediaHandler extends Abstractlist<PSXMediaAbstract> class
 *   it could handle serialze/deserialize, and storing the source file
 * - consider making CDSectorReader completely threadsafe by changing
 *   SeekToSector() to CDXASectorHeader ReadSectorHeader(iSect).
 *   And I guess with that I should put ReadSector() in with the CDXASectorHeader,
 *   and I should have CDSector classes extend CDXASector.
 * - moving all CD indexing related stuff into its own sub package, and all
 *   decoding related stuff into its own package, and just keep Main and 
 *   settings in the root package.
 *
 */


package jpsxdec;


import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import javax.sound.sampled.*;
import jpsxdec.CDSectorReader.CDXASector;
import jpsxdec.util.Yuv4mpeg2;

public class Main {
    
    public static int DebugVerbose = 2;
    
    public static void main(String[] args) {
        
        Settings.ProcessArguments(args);
        
        // set verbosity
        int[] aiVerbosityLevels = Settings.getVerbosityLevels();
        Main.DebugVerbose = aiVerbosityLevels[0];
        PSXMediaAbstract.DebugVerbose = aiVerbosityLevels[1];
        StrFrameDemuxerIS.DebugVerbose = aiVerbosityLevels[2];
        StrFrameUncompressorIS.DebugVerbose = aiVerbosityLevels[3];
        StrFrameMDEC.DebugVerbose = aiVerbosityLevels[4];
        
        switch (Settings.getMainCommandType()) {
            case Settings.INDEX_ONLY:
                System.exit(IndexOnly());
                break;
            case Settings.DECODE_ONE:
            case Settings.DECODE_ALL:
                System.exit(NormalDecode());
                break;
            case Settings.SPECIAL_IN_FILE:
                System.exit(DecodeSpecialFrameFile());
                break;
            case Settings.DECODE_SECTORS_FRAME:
                System.exit(DecodeFrameSectors());
                break;
            case Settings.DECODE_SECTORS_AUDIO:
                System.exit(DecodeAudioSectors());
                break;
            case Settings.SECTOR_LIST:
                System.exit(SectorList());
                break;
        }
        
        
        return;
    }
    
    //--------------------------------------------------------------------------
    
    private static int IndexOnly() {
        //open input file
        CDSectorReader oCD;
        try {
            oCD = new CDSectorReader(Settings.getInputFile());
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }
        
        // index the STR file
        ArrayList<PSXMediaAbstract> oMedias;
        boolean blnSaveIndexFile = false;
        try {
            PrintStream oPrinter;
            if (Settings.getIndexFile().equals("-"))
                oPrinter = System.out;
            else
                oPrinter = new PrintStream(Settings.getIndexFile());
            oMedias = PSXMediaAbstract.IndexCD(oCD);
            PSXMediaAbstract.SerializeMediaList(oMedias, oPrinter);
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }

        return 0;
    }
    
    //--------------------------------------------------------------------------
    
    private static int NormalDecode() {
        //open input file
        CDSectorReader oCD;
        try {
            oCD = new CDSectorReader(Settings.getInputFile());
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }
        
        if (!oCD.HasSectorHeader() && DebugVerbose > 0) {
            System.err.println("Warning: Input file does not contain entire raw CD sectors.");
            System.err.println("         Audio cannot be decoded.");
        }
        
        // index the STR file
        ArrayList<PSXMediaAbstract> oMedias;
        boolean blnSaveIndexFile = false;
        try {
            String sIndexFile = Settings.getIndexFile();
            if (sIndexFile  != null) {
                if (new File(Settings.getIndexFile()).exists()) {
                    oMedias = PSXMediaAbstract.IndexCD(oCD, sIndexFile);
                } else {
                    oMedias = PSXMediaAbstract.IndexCD(oCD);
                    blnSaveIndexFile = true;
                }
            } else {
                oMedias = PSXMediaAbstract.IndexCD(oCD);
            }
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }

        // save index file if necessary
        if (blnSaveIndexFile) {
            try {
                PrintStream oPrinter;
                if (Settings.getIndexFile().equals("-"))
                    oPrinter = System.out;
                else
                    oPrinter = new PrintStream(Settings.getIndexFile());
                PSXMediaAbstract.SerializeMediaList(oMedias, oPrinter);
            } catch (IOException ex) {
                if (DebugVerbose > 2)
                    ex.printStackTrace();
                else
                    System.err.println(ex.getMessage());
                return -1;
            }
        }

        // decode the desired media 
        
        int iMediaStart, iMediaEnd, iMediaIndex;

        if (Settings.getMainCommandType() == Settings.DECODE_ALL) {
            iMediaStart = 0;
            iMediaEnd = oMedias.size()-1;
        } else {
            iMediaStart = Settings.getItemToDecode();
            if (iMediaStart < 0) iMediaStart = 0;
            if (iMediaStart > oMedias.size()-1) iMediaStart = oMedias.size()-1;
            iMediaEnd = iMediaStart;
        }
        
        
        for (iMediaIndex = iMediaStart; iMediaIndex <= iMediaEnd; iMediaIndex++) 
        {
            PSXMediaAbstract oMedia = oMedias.get(iMediaIndex);

            // movie frames
            if (Settings.DecodeVideo() && (oMedia instanceof CDMediaSTR)) {
                CDMediaSTR oMovie = (CDMediaSTR)oMedia;
                DecodeVideo(oMovie, iMediaIndex);
                
            } // movie frames

            // movie audio
            if (Settings.DecodeAudio() && (oMedia instanceof CDMediaSTR)) {
                CDMediaSTR oMovie = (CDMediaSTR)oMedia;
                try {
                    ArrayList<PSXSector> oChunks = 
                            oMovie.GetAudioSectors();
                    if (oChunks != null) {
                        StrAudioDemuxerDecoderIS dec = 
                                new StrAudioDemuxerDecoderIS(oChunks);
                        AudioInputStream str = 
                                new AudioInputStream(dec, dec.getFormat(), dec.getLength());
                        AudioSystem.write(str, AudioFileFormat.Type.WAVE, 
                                new File(String.format(
                                "%s%03d.wav",
                                Settings.getOutputFile(), iMediaIndex
                                )));
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
                
            if (Settings.DecodeAudio() && (oMedia instanceof CDMediaXA)) {
                CDMediaXA oAudio = (CDMediaXA)oMedia;
                try {

                    int iChannelStart, iChannelEnd, iChannelIndex;
                    if (Settings.getChannel() < 0) {
                        iChannelStart = 0;
                        iChannelEnd = 32;
                    } else {
                        iChannelStart = Settings.getChannel();
                        iChannelEnd = iChannelStart;
                    }
                    
                    for (iChannelIndex = iChannelStart; iChannelIndex <= iChannelEnd; iChannelIndex++) {
                        ArrayList<PSXSector> oChunks =
                                oAudio.GetChannelSectors(iChannelIndex);

                        if (oChunks != null)
                        {
                            StrAudioDemuxerDecoderIS dec = 
                                    new StrAudioDemuxerDecoderIS(oChunks);
                            AudioInputStream str = 
                                    new AudioInputStream(dec, dec.getFormat(), dec.getLength());
                            AudioSystem.write(str, AudioFileFormat.Type.WAVE, 
                                    new File(String.format(
                                    "%s%03d_ch%02d.wav",
                                    Settings.getOutputFile(), 
                                    iMediaIndex, 
                                    iChannelIndex
                                    )));
                        }
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

            }

        }
            
        return 0;
    }
    
    //..........................................................................
    
    private static void DecodeVideo(CDMediaSTR oMovie, int iMediaIndex) {
        long iFrameStart = Settings.getStartFrame();
        long iFrameEnd = Settings.getEndFrame();
        long iFrameIndex;

        if (iFrameEnd < 0) iFrameEnd = oMovie.GetEndFrame();

        if (iFrameStart < oMovie.GetStartFrame()) 
            iFrameStart = oMovie.GetStartFrame();
        if (iFrameStart > oMovie.GetEndFrame())
            iFrameStart = oMovie.GetEndFrame();

        if (iFrameEnd < oMovie.GetStartFrame())
            iFrameEnd = oMovie.GetStartFrame();
        if (iFrameEnd > oMovie.GetEndFrame()) 
            iFrameEnd = oMovie.GetEndFrame();

        if (iFrameStart > iFrameEnd) {
            long lng = iFrameStart;
            iFrameStart = iFrameEnd;
            iFrameEnd = lng;
        }

        for (iFrameIndex = iFrameStart; iFrameIndex <= iFrameEnd; iFrameIndex++) 
        {
            String sFrameFile = String.format(
                        "%s%03d_f%04d.%s",
                        Settings.getOutputFile(), 
                        iMediaIndex, 
                        iFrameIndex,
                        Settings.getOutputFormat());
            try {
                
                ArrayList<PSXSector> oSectors = 
                        oMovie.GetFrameSectors(iFrameIndex);
                StrFrameDemuxerIS str = new StrFrameDemuxerIS(oSectors);
                System.err.println("Reading frame " + iFrameIndex);

                DecodeAndSaveFrame("demux", str, sFrameFile);
                

            } catch (IOException ex) {
                ex.printStackTrace();
            }

        } // for
    }
    
    //--------------------------------------------------------------------------
    
    private static int DecodeFrameSectors() {
        CDSectorReader oCD;
        try {
            oCD = new CDSectorReader(Settings.getInputFile());
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }
        
        ArrayList<PSXSector> oSectors = GetSettingSectors(oCD);
        
        StrFrameDemuxerIS str = new StrFrameDemuxerIS(oSectors);
        
        String sFrameFile = String.format(
                    "%s.%s",
                    Settings.getOutputFile(), 
                    Settings.getOutputFormat());
        try {
            
            DecodeAndSaveFrame("demux", str, sFrameFile);
        } catch (IOException ex) {
            ex.printStackTrace();
            return -1;
        }
        
        return 0;
    }
    
    //..........................................................................
    
    private static int DecodeAudioSectors() {
        CDSectorReader oCD;
        try {
            oCD = new CDSectorReader(Settings.getInputFile());
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }
        
        ArrayList<PSXSector> oSectors = GetSettingSectors(oCD);
        
        StrAudioDemuxerDecoderIS dec = new StrAudioDemuxerDecoderIS(oSectors);
        AudioInputStream str = 
                new AudioInputStream(dec, dec.getFormat(), dec.getLength());
        try {
            AudioSystem.write(str, AudioFileFormat.Type.WAVE, 
                    new File(String.format(
                    "%s.wav",
                    Settings.getOutputFile()
                    )));
        } catch (IOException ex) {
            ex.printStackTrace();
            return -1;
        }
        
        return 0;
    }

    //..........................................................................
    
    private static ArrayList<PSXSector> GetSettingSectors(CDSectorReader oCD) 
    {
        ArrayList<PSXSector> oSectors = 
                new ArrayList<PSXSector>();
        for (int i : Settings.getSectorList()) {
            try {
                CDXASector oCDXA = oCD.get(i);
                PSXSector oSect = 
                        PSXSector.SectorIdentifyFactory(oCDXA );
                if (oSect != null)
                    oSectors.add(oSect);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return oSectors;
    }

    //..........................................................................
    
    private static int DecodeSpecialFrameFile() {
        
        String sFrameFile = String.format(
                    "%s.%s",
                    Settings.getOutputFile(), 
                    Settings.getOutputFormat());
        try {
            
            InputStream is;
            is = new FileInputStream(Settings.getInputFile());
            
            DecodeAndSaveFrame(Settings.getInputFileFormat(), is, sFrameFile);
            
        } catch (IOException ex) {
            ex.printStackTrace();
            return -1;
        }
        
        return 0;
    }

    //--------------------------------------------------------------------------
   
    private static void DecodeAndSaveFrame(String sInputType, 
                                           InputStream str, 
                                           String sFrameFile) 
            throws IOException 
    {
        long lngWidth;
        long lngHeight;
        
        if (str instanceof StrFrameDemuxerIS) {
            lngWidth = ((StrFrameDemuxerIS)str).getWidth();
            lngHeight = ((StrFrameDemuxerIS)str).getHeight();
        } else if (str instanceof StrFrameUncompressorIS) {
            lngWidth = ((StrFrameUncompressorIS)str).getWidth();
            lngHeight = ((StrFrameUncompressorIS)str).getHeight();
        } else {
            lngWidth = Settings.getWidth();
            lngHeight = Settings.getHeight();
        }
            
        
        if (sInputType.equals("demux")) {

            if (Settings.getOutputFormat().equals("demux")) {
                FileOutputStream fos = new FileOutputStream(sFrameFile);
                int ib;
                while ((ib = str.read()) > 0)
                    fos.write(ib);
                fos.close();
                return;
            }

            str = new StrFrameUncompressorIS(str, lngWidth, lngHeight);
            if (Settings.getOutputFormat().equals("0rlc")) {
                FileOutputStream fos = new FileOutputStream(sFrameFile);
                int ib;
                while ((ib = str.read()) > 0)
                    fos.write(ib);
                fos.close();
                return;
            }

        }
        
        Yuv4mpeg2 oYuv = StrFrameMDEC.DecodeFrame(str, lngWidth, lngHeight);
        if (Settings.getOutputFormat().equals("yuv") ||
            Settings.getOutputFormat().equals("y4m")) 
        {
            FileOutputStream fos = new FileOutputStream(sFrameFile);
            oYuv.Write(fos);
            fos.close();
            return;
        }

        BufferedImage bi = oYuv.toBufferedImage();
        ImageIO.write(bi, Settings.getOutputFormat(), new File(sFrameFile));
    }

    //--------------------------------------------------------------------------

    private static int SectorList() {
        CDSectorReader oCD;
        try {
            oCD = new CDSectorReader(Settings.getInputFile());
        } catch (IOException ex) {
            if (DebugVerbose > 2)
                ex.printStackTrace();
            else
                System.err.println(ex.getMessage());
            return -1;
        }
        
        PrintStream ps;
        try {
            if (Settings.getOutputFile().equals("-"))
                ps = System.out;
            else
                ps = new PrintStream(Settings.getOutputFile());
            
            for (CDXASector oCDXASect : oCD) {
                PSXSector oSect = 
                        PSXSector.SectorIdentifyFactory(oCDXASect);
                if (oSect != null)
                    ps.println(oSect.toString());
            }
            
            ps.flush();
            if (!ps.equals(System.out)) ps.close();
            return 0;
            
        } catch (IOException ex) {
            ex.printStackTrace();
            return -1;
        }
    }

    //--------------------------------------------------------------------------
    //--------------------------------------------------------------------------
    
    private static void LainPack() {
            
        try {
            Lain_LAPKS lnpk = 
                new Lain_LAPKS("D:\\LAPKS.BIN");
            for (int i = 0; true; i++) {
                StrFrameUncompressorIS dec = 
                        new StrFrameUncompressorIS(
                            lnpk, 
                            lnpk.getCurrentCellWidth(), 
                            lnpk.getCurrentCellHeight());
                Yuv4mpeg2 yuv =
                        StrFrameMDEC.DecodeFrame(
                            dec, 
                            lnpk.getCurrentCellWidth(), 
                            lnpk.getCurrentCellHeight());
                BufferedImage bi = yuv.toBufferedImage();
                ImageIO.write(bi, "png", new File("pose" + i + ".png"));
                bi = lnpk.ReadBitMask();
                ImageIO.write(bi, "png", new File("pose" + i + "mask.png"));
                lnpk.MoveToNext();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

             
    }
    
}