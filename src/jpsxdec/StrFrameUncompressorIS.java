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
 * StrFrameUncompresserIS.java
 *
 */

package jpsxdec;

import java.io.*;
import java.util.LinkedList;
import jpsxdec.util.BufferedBitReader;
import jpsxdec.util.IGetFilePointer;

/** Class to decode/uncompress the demuxed video frame data from a 
 *  Playstation disc. */
public class StrFrameUncompressorIS 
    extends InputStream 
    implements IGetFilePointer 
{
    /*########################################################################*/
    /*## Static stuff ########################################################*/
    /*########################################################################*/
    
    public static int DebugVerbose = 2;
    
    /* ---------------------------------------------------------------------- */
    /* STR version 3 frames stuff ------------------------------------------- */
    /* ---------------------------------------------------------------------- */
    // The following is stuff specific for version 3 frames
    
    /** Holds information for the version 3 DC variable length code lookup */
    private static class DCVariableLengthCode {
        public String VariableLengthCode;
        public int DC_Length;
        public int DC_DifferentialLookup[];
        
        /** Constructor */
        public DCVariableLengthCode(String vlc, int len, int differential[]) {
            VariableLengthCode = vlc;
            DC_Length = len;
            DC_DifferentialLookup = differential;
        }
    }
    
    // .........................................................................
    
    /* From the offical MPEG-1 ISO standard specification (ISO 11172).
     * Specifically table 2-D.12 in 11172-2. 
     * These tables are only used for version 3 STR frames. */
     
    /** The longest of all the DC Chrominance variable-length-codes is 8 bits */
    private final static int DC_CHROMINANCE_LONGEST_VARIABLE_LENGTH_CODE = 8;
    
    /** Table of DC Chrominance (Cr, Cb) variable length codes */
    private final static 
                DCVariableLengthCode DC_Chrominance_VariableLengthCodes[] = 
    {                    // code,  length,  differential lookup list
new DCVariableLengthCode("11111110" , 8, DCDifferential(-255, -128,  128, 255)),
new DCVariableLengthCode("1111110"  , 7, DCDifferential(-127,  -64,   64, 127)),
new DCVariableLengthCode("111110"   , 6, DCDifferential( -63,  -32,   32,  63)),
new DCVariableLengthCode("11110"    , 5, DCDifferential( -31,  -16,   16,  31)),
new DCVariableLengthCode("1110"     , 4, DCDifferential( -15,   -8,    8,  15)),
new DCVariableLengthCode("110"      , 3, DCDifferential(  -7,   -4,    4,   7)),
new DCVariableLengthCode("10"       , 2, DCDifferential(  -3,   -2,    2,   3)),
new DCVariableLengthCode("01"       , 1, DCDifferential(  -1,   -1,    1,   1)),
new DCVariableLengthCode("00"       , 0, null)
    };
    
    
    /** The longest of all the DC Luminance variable-length-codes is 7 bits */
    private final static int DC_LUMINANCE_LONGEST_VARIABLE_LENGTH_CODE = 7;
    
    /** Table of DC Luminance (Y1, Y2, Y3, Y4) variable length codes */
    private final static 
                DCVariableLengthCode DC_Luminance_VariableLengthCodes[] = 
    {                    // code,  length,  differential lookup list
new DCVariableLengthCode("1111110" , 8,  DCDifferential(-255, -128,  128, 255)),
new DCVariableLengthCode("111110"  , 7,  DCDifferential(-127,  -64,   64, 127)),
new DCVariableLengthCode("11110"   , 6,  DCDifferential( -63,  -32,   32,  63)),
new DCVariableLengthCode("1110"    , 5,  DCDifferential( -31,  -16,   16,  31)),
new DCVariableLengthCode("110"     , 4,  DCDifferential( -15,   -8,    8,  15)),
new DCVariableLengthCode("101"     , 3,  DCDifferential(  -7,   -4,    4,   7)),
new DCVariableLengthCode("01"      , 2,  DCDifferential(  -3,   -2,    2,   3)),
new DCVariableLengthCode("00"      , 1,  DCDifferential(  -1,   -1,    1,   1)),
new DCVariableLengthCode("100"     , 0,  null)            
    };

    /** Construct a DC differential lookup list. Used only for the
     * DC_Chrominance_VariableLengthCodes and DC_Luminance_VariableLengthCodes 
     * lists. */
    private static int[] DCDifferential(int iNegitiveStart, int iNegitiveEnd, 
                                        int iPositiveStart, int iPositiveEnd) 
    {
        int aiDifferentialArray[];
        
        int iArraySize = (iNegitiveEnd - iNegitiveStart + 1) 
                       + (iPositiveEnd - iPositiveStart + 1);
        
        aiDifferentialArray = new int[iArraySize];
        int iDifferentialArrayIndex = 0;
        
        for (int i = iNegitiveStart; i <= iNegitiveEnd; i++) {
            aiDifferentialArray[iDifferentialArrayIndex] = i;
            iDifferentialArrayIndex++;
        }
        
        for (int i = iPositiveStart; i <= iPositiveEnd; i++) {
            aiDifferentialArray[iDifferentialArrayIndex] = i;
            iDifferentialArrayIndex++;
        }
        
        return aiDifferentialArray;
    }
    
    /* ---------------------------------------------------------------------- */
    /* AC Variable length code stuff ---------------------------------------- */
    /* ---------------------------------------------------------------------- */
    
    /** Holds information about AC variable length code lookup */
    private static class ACVariableLengthCode {
        public String VariableLengthCode;
        public int RunOfZeros;
        public int AbsoluteLevel;
        
        /** Constructor */
        public ACVariableLengthCode(String vlc, int run, int level)
        {
            VariableLengthCode = vlc;
            RunOfZeros = run;
            AbsoluteLevel = level;
        }
    }
	
    // .........................................................................
    
    /** Sequence of bits indicating an escape code */
    private final static String AC_ESCAPE_CODE = "000001";
    
    /** Sequence of bits indicating the end of a block.
     * Unlike the MPEG1 specification, these bits can, and often do appear as 
     * the first and only variable-length-code in a block. */
    private final static String VLC_END_OF_BLOCK = "10"; // bits 10
    
    /** The longest of all the AC variable-length-codes is 16 bits */
    private final static int AC_LONGEST_VARIABLE_LENGTH_CODE = 16;
    
    private final static ACVariableLengthCode AC_VARIABLE_LENGTH_CODES_MPEG1[] = 
    {
	/*
        new ACVariableLengthCode("1"                 , 0 , 1  ),
	  The MPEG1 specification declares that if the first 
          variable-length-code in a block is "1" that it should be translated
          to the run-length-code (0, 1). The PSX variable-length-code
          decoding does not follow this rule. 
         */
        
                             //  Code               "Run" "Level"
        new ACVariableLengthCode("11"                , 0 , 1  ),
        new ACVariableLengthCode("011"               , 1 , 1  ),
        new ACVariableLengthCode("0100"              , 0 , 2  ),
        new ACVariableLengthCode("0101"              , 2 , 1  ),
        new ACVariableLengthCode("00101"             , 0 , 3  ),
        new ACVariableLengthCode("00110"             , 4 , 1  ),
        new ACVariableLengthCode("00111"             , 3 , 1  ),
        new ACVariableLengthCode("000100"            , 7 , 1  ),
        new ACVariableLengthCode("000101"            , 6 , 1  ),
        new ACVariableLengthCode("000110"            , 1 , 2  ),
        new ACVariableLengthCode("000111"            , 5 , 1  ),
        new ACVariableLengthCode("0000100"           , 2 , 2  ),
        new ACVariableLengthCode("0000101"           , 9 , 1  ),
        new ACVariableLengthCode("0000110"           , 0 , 4  ),
        new ACVariableLengthCode("0000111"           , 8 , 1  ),
        new ACVariableLengthCode("00100000"          , 13, 1  ),
        new ACVariableLengthCode("00100001"          , 0 , 6  ),
        new ACVariableLengthCode("00100010"          , 12, 1  ),
        new ACVariableLengthCode("00100011"          , 11, 1  ),
        new ACVariableLengthCode("00100100"          , 3 , 2  ),
        new ACVariableLengthCode("00100101"          , 1 , 3  ),
        new ACVariableLengthCode("00100110"          , 0 , 5  ),
        new ACVariableLengthCode("00100111"          , 10, 1  ),
        new ACVariableLengthCode("0000001000"        , 16, 1  ),
        new ACVariableLengthCode("0000001001"        , 5 , 2  ),
        new ACVariableLengthCode("0000001010"        , 0 , 7  ),
        new ACVariableLengthCode("0000001011"        , 2 , 3  ),
        new ACVariableLengthCode("0000001100"        , 1 , 4  ),
        new ACVariableLengthCode("0000001101"        , 15, 1  ),
        new ACVariableLengthCode("0000001110"        , 14, 1  ),
        new ACVariableLengthCode("0000001111"        , 4 , 2  ),
        new ACVariableLengthCode("000000010000"      , 0 , 11 ),
        new ACVariableLengthCode("000000010001"      , 8 , 2  ),
        new ACVariableLengthCode("000000010010"      , 4 , 3  ),
        new ACVariableLengthCode("000000010011"      , 0 , 10 ),
        new ACVariableLengthCode("000000010100"      , 2 , 4  ),
        new ACVariableLengthCode("000000010101"      , 7 , 2  ),
        new ACVariableLengthCode("000000010110"      , 21, 1  ),
        new ACVariableLengthCode("000000010111"      , 20, 1  ),
        new ACVariableLengthCode("000000011000"      , 0 , 9  ),
        new ACVariableLengthCode("000000011001"      , 19, 1  ),
        new ACVariableLengthCode("000000011010"      , 18, 1  ),
        new ACVariableLengthCode("000000011011"      , 1 , 5  ),
        new ACVariableLengthCode("000000011100"      , 3 , 3  ),
        new ACVariableLengthCode("000000011101"      , 0 , 8  ),
        new ACVariableLengthCode("000000011110"      , 6 , 2  ),
        new ACVariableLengthCode("000000011111"      , 17, 1  ),
        new ACVariableLengthCode("0000000010000"     , 10, 2  ),
        new ACVariableLengthCode("0000000010001"     , 9 , 2  ),
        new ACVariableLengthCode("0000000010010"     , 5 , 3  ),
        new ACVariableLengthCode("0000000010011"     , 3 , 4  ),
        new ACVariableLengthCode("0000000010100"     , 2 , 5  ),
        new ACVariableLengthCode("0000000010101"     , 1 , 7  ),
        new ACVariableLengthCode("0000000010110"     , 1 , 6  ),
        new ACVariableLengthCode("0000000010111"     , 0 , 15 ),
        new ACVariableLengthCode("0000000011000"     , 0 , 14 ),
        new ACVariableLengthCode("0000000011001"     , 0 , 13 ),
        new ACVariableLengthCode("0000000011010"     , 0 , 12 ),
        new ACVariableLengthCode("0000000011011"     , 26, 1  ),
        new ACVariableLengthCode("0000000011100"     , 25, 1  ),
        new ACVariableLengthCode("0000000011101"     , 24, 1  ),
        new ACVariableLengthCode("0000000011110"     , 23, 1  ),
        new ACVariableLengthCode("0000000011111"     , 22, 1  ),
        new ACVariableLengthCode("00000000010000"    , 0 , 31 ),
        new ACVariableLengthCode("00000000010001"    , 0 , 30 ),
        new ACVariableLengthCode("00000000010010"    , 0 , 29 ),
        new ACVariableLengthCode("00000000010011"    , 0 , 28 ),
        new ACVariableLengthCode("00000000010100"    , 0 , 27 ),
        new ACVariableLengthCode("00000000010101"    , 0 , 26 ),
        new ACVariableLengthCode("00000000010110"    , 0 , 25 ),
        new ACVariableLengthCode("00000000010111"    , 0 , 24 ),
        new ACVariableLengthCode("00000000011000"    , 0 , 23 ),
        new ACVariableLengthCode("00000000011001"    , 0 , 22 ),
        new ACVariableLengthCode("00000000011010"    , 0 , 21 ),
        new ACVariableLengthCode("00000000011011"    , 0 , 20 ),
        new ACVariableLengthCode("00000000011100"    , 0 , 19 ),
        new ACVariableLengthCode("00000000011101"    , 0 , 18 ),
        new ACVariableLengthCode("00000000011110"    , 0 , 17 ),
        new ACVariableLengthCode("00000000011111"    , 0 , 16 ),
        new ACVariableLengthCode("000000000010000"   , 0 , 40 ),
        new ACVariableLengthCode("000000000010001"   , 0 , 39 ),
        new ACVariableLengthCode("000000000010010"   , 0 , 38 ),
        new ACVariableLengthCode("000000000010011"   , 0 , 37 ),
        new ACVariableLengthCode("000000000010100"   , 0 , 36 ),
        new ACVariableLengthCode("000000000010101"   , 0 , 35 ),
        new ACVariableLengthCode("000000000010110"   , 0 , 34 ),
        new ACVariableLengthCode("000000000010111"   , 0 , 33 ),
        new ACVariableLengthCode("000000000011000"   , 0 , 32 ),
        new ACVariableLengthCode("000000000011001"   , 1 , 14 ),
        new ACVariableLengthCode("000000000011010"   , 1 , 13 ),
        new ACVariableLengthCode("000000000011011"   , 1 , 12 ),
        new ACVariableLengthCode("000000000011100"   , 1 , 11 ),
        new ACVariableLengthCode("000000000011101"   , 1 , 10 ),
        new ACVariableLengthCode("000000000011110"   , 1 , 9  ),
        new ACVariableLengthCode("000000000011111"   , 1 , 8  ),
        new ACVariableLengthCode("0000000000010000"  , 1 , 18 ),
        new ACVariableLengthCode("0000000000010001"  , 1 , 17 ),
        new ACVariableLengthCode("0000000000010010"  , 1 , 16 ),
        new ACVariableLengthCode("0000000000010011"  , 1 , 15 ),
        new ACVariableLengthCode("0000000000010100"  , 6 , 3  ),
        new ACVariableLengthCode("0000000000010101"  , 16, 2  ),
        new ACVariableLengthCode("0000000000010110"  , 15, 2  ),
        new ACVariableLengthCode("0000000000010111"  , 14, 2  ),
        new ACVariableLengthCode("0000000000011000"  , 13, 2  ),
        new ACVariableLengthCode("0000000000011001"  , 12, 2  ),
        new ACVariableLengthCode("0000000000011010"  , 11, 2  ),
        new ACVariableLengthCode("0000000000011011"  , 31, 1  ),
        new ACVariableLengthCode("0000000000011100"  , 30, 1  ),
        new ACVariableLengthCode("0000000000011101"  , 29, 1  ),
        new ACVariableLengthCode("0000000000011110"  , 28, 1  ),
        new ACVariableLengthCode("0000000000011111"  , 27, 1  )
    };

    /** The custom Serial Experiments Lain Playstation game
     *  variable-length-code table */
    private final static ACVariableLengthCode AC_VARIABLE_LENGTH_CODES_LAIN[] = 
    {
                              // Code               "Run" "Level"
        new ACVariableLengthCode("11"                , 0  , 1  ),
        new ACVariableLengthCode("011"               , 0  , 2  ),
        new ACVariableLengthCode("0100"              , 1  , 1  ),
        new ACVariableLengthCode("0101"              , 0  , 3  ),
        new ACVariableLengthCode("00101"             , 0  , 4  ),
        new ACVariableLengthCode("00110"             , 2  , 1  ),
        new ACVariableLengthCode("00111"             , 0  , 5  ),
        new ACVariableLengthCode("000100"            , 0  , 6  ),
        new ACVariableLengthCode("000101"            , 3  , 1  ),
        new ACVariableLengthCode("000110"            , 1  , 2  ),
        new ACVariableLengthCode("000111"            , 0  , 7  ),
        new ACVariableLengthCode("0000100"           , 0  , 8  ),
        new ACVariableLengthCode("0000101"           , 4  , 1  ),
        new ACVariableLengthCode("0000110"           , 0  , 9  ),
        new ACVariableLengthCode("0000111"           , 5  , 1  ),
        new ACVariableLengthCode("00100000"          , 0  , 10 ),
        new ACVariableLengthCode("00100001"          , 0  , 11 ),
        new ACVariableLengthCode("00100010"          , 1  , 3  ),
        new ACVariableLengthCode("00100011"          , 6  , 1  ),
        new ACVariableLengthCode("00100100"          , 0  , 12 ),
        new ACVariableLengthCode("00100101"          , 0  , 13 ),
        new ACVariableLengthCode("00100110"          , 7  , 1  ),
        new ACVariableLengthCode("00100111"          , 0  , 14 ),
        new ACVariableLengthCode("0000001000"        , 0  , 15 ),
        new ACVariableLengthCode("0000001001"        , 2  , 2  ),
        new ACVariableLengthCode("0000001010"        , 8  , 1  ),
        new ACVariableLengthCode("0000001011"        , 1  , 4  ),
        new ACVariableLengthCode("0000001100"        , 0  , 16 ),
        new ACVariableLengthCode("0000001101"        , 0  , 17 ),
        new ACVariableLengthCode("0000001110"        , 9  , 1  ),
        new ACVariableLengthCode("0000001111"        , 0  , 18 ),
        new ACVariableLengthCode("000000010000"      , 0  , 19 ),
        new ACVariableLengthCode("000000010001"      , 1  , 5  ),
        new ACVariableLengthCode("000000010010"      , 0  , 20 ),
        new ACVariableLengthCode("000000010011"      , 10 , 1  ),
        new ACVariableLengthCode("000000010100"      , 0  , 21 ),
        new ACVariableLengthCode("000000010101"      , 3  , 2  ),
        new ACVariableLengthCode("000000010110"      , 12 , 1  ),
        new ACVariableLengthCode("000000010111"      , 0  , 23 ),
        new ACVariableLengthCode("000000011000"      , 0  , 22 ),
        new ACVariableLengthCode("000000011001"      , 11 , 1  ),
        new ACVariableLengthCode("000000011010"      , 0  , 24 ),
        new ACVariableLengthCode("000000011011"      , 0  , 28 ),
        new ACVariableLengthCode("000000011100"      , 0  , 25 ),
        new ACVariableLengthCode("000000011101"      , 1  , 6  ),
        new ACVariableLengthCode("000000011110"      , 2  , 3  ),
        new ACVariableLengthCode("000000011111"      , 0  , 27 ),
        new ACVariableLengthCode("0000000010000"     , 0  , 26 ),
        new ACVariableLengthCode("0000000010001"     , 13 , 1  ),
        new ACVariableLengthCode("0000000010010"     , 0  , 29 ),
        new ACVariableLengthCode("0000000010011"     , 1  , 7  ),
        new ACVariableLengthCode("0000000010100"     , 4  , 2  ),
        new ACVariableLengthCode("0000000010101"     , 0  , 31 ),
        new ACVariableLengthCode("0000000010110"     , 0  , 30 ),
        new ACVariableLengthCode("0000000010111"     , 14 , 1  ),
        new ACVariableLengthCode("0000000011000"     , 0  , 32 ),
        new ACVariableLengthCode("0000000011001"     , 0  , 33 ),
        new ACVariableLengthCode("0000000011010"     , 1  , 8  ),
        new ACVariableLengthCode("0000000011011"     , 0  , 35 ),
        new ACVariableLengthCode("0000000011100"     , 0  , 34 ),
        new ACVariableLengthCode("0000000011101"     , 5  , 2  ),
        new ACVariableLengthCode("0000000011110"     , 0  , 36 ),
        new ACVariableLengthCode("0000000011111"     , 0  , 37 ),
        new ACVariableLengthCode("00000000010000"    , 2  , 4  ),
        new ACVariableLengthCode("00000000010001"    , 1  , 9  ),
        new ACVariableLengthCode("00000000010010"    , 1  , 24 ),
        new ACVariableLengthCode("00000000010011"    , 0  , 38 ),
        new ACVariableLengthCode("00000000010100"    , 15 , 1  ),
        new ACVariableLengthCode("00000000010101"    , 0  , 39 ),
        new ACVariableLengthCode("00000000010110"    , 3  , 3  ),
        new ACVariableLengthCode("00000000010111"    , 7  , 3  ),
        new ACVariableLengthCode("00000000011000"    , 0  , 40 ),
        new ACVariableLengthCode("00000000011001"    , 0  , 41 ),
        new ACVariableLengthCode("00000000011010"    , 0  , 42 ),
        new ACVariableLengthCode("00000000011011"    , 0  , 43 ),
        new ACVariableLengthCode("00000000011100"    , 1  , 10 ),
        new ACVariableLengthCode("00000000011101"    , 0  , 44 ),
        new ACVariableLengthCode("00000000011110"    , 6  , 2  ),
        new ACVariableLengthCode("00000000011111"    , 0  , 45 ),
        new ACVariableLengthCode("000000000010000"   , 0  , 47 ),
        new ACVariableLengthCode("000000000010001"   , 0  , 46 ),
        new ACVariableLengthCode("000000000010010"   , 16 , 1  ),
        new ACVariableLengthCode("000000000010011"   , 2  , 5  ),
        new ACVariableLengthCode("000000000010100"   , 0  , 48 ),
        new ACVariableLengthCode("000000000010101"   , 1  , 11 ),
        new ACVariableLengthCode("000000000010110"   , 0  , 49 ),
        new ACVariableLengthCode("000000000010111"   , 0  , 51 ),
        new ACVariableLengthCode("000000000011000"   , 0  , 50 ),
        new ACVariableLengthCode("000000000011001"   , 7  , 2  ),
        new ACVariableLengthCode("000000000011010"   , 0  , 52 ),
        new ACVariableLengthCode("000000000011011"   , 4  , 3  ),
        new ACVariableLengthCode("000000000011100"   , 0  , 53 ),
        new ACVariableLengthCode("000000000011101"   , 17 , 1  ),
        new ACVariableLengthCode("000000000011110"   , 1  , 12 ),
        new ACVariableLengthCode("000000000011111"   , 0  , 55 ),
        new ACVariableLengthCode("0000000000010000"  , 0  , 54 ),
        new ACVariableLengthCode("0000000000010001"  , 0  , 56 ),
        new ACVariableLengthCode("0000000000010010"  , 0  , 57 ),
        new ACVariableLengthCode("0000000000010011"  , 21 , 1  ),
        new ACVariableLengthCode("0000000000010100"  , 0  , 58 ),
        new ACVariableLengthCode("0000000000010101"  , 3  , 4  ),
        new ACVariableLengthCode("0000000000010110"  , 1  , 13 ),
        new ACVariableLengthCode("0000000000010111"  , 23 , 1  ),
        new ACVariableLengthCode("0000000000011000"  , 8  , 2  ),
        new ACVariableLengthCode("0000000000011001"  , 0  , 59 ),
        new ACVariableLengthCode("0000000000011010"  , 2  , 6  ),
        new ACVariableLengthCode("0000000000011011"  , 19 , 1  ),
        new ACVariableLengthCode("0000000000011100"  , 0  , 60 ),
        new ACVariableLengthCode("0000000000011101"  , 9  , 2  ),
        new ACVariableLengthCode("0000000000011110"  , 24 , 1  ),
        new ACVariableLengthCode("0000000000011111"  , 18 , 1  )
    };   
     
    /*########################################################################*/
    /*## Beginning of instance ###############################################*/
    /*########################################################################*/
    
    /** Binary bit reader encapsulates the InputStream to read as a bit stream*/
    BufferedBitReader m_oBitReader;
    
    /** A queue to store the uncompressed data as MDEC codes */
    LinkedList<StrFrameMDEC.Mdec16Bits> m_oReaderQueue = 
            new LinkedList<StrFrameMDEC.Mdec16Bits>();
    /** Holds information about the current MDEC 16 bits being read */
    StrFrameMDEC.Mdec16Bits m_oCurrent16Bits = null;
    /** Alternate between reading the high byte and the low byte */
    boolean m_blnLowHighReadToggle = true;
    
    // Frame info
    private long m_lngNumberOfRunLenthCodes;
    private long m_lngHeader3800;
    private long m_lngQuantizationScaleChrom;
    private long m_lngQuantizationScaleLumin;
    
    /** Most games use verion 2 or version 3. Currently handled exceptions
     *  are: FF7 uses version 1, Lain uses version 0 */
    private long m_lngVersion;

    /** Width of the frame in pixels */
    private long m_lngWidth;
    /** Height of the frame in pixels */
    private long m_lngHeight;
    
    // For version 3 frames, all DC Coefficients are relative
    // TODO: Maybe try to encapsulate this somehow
    private int m_iPreviousCr_DC = 0;
    private int m_iPreviousCb_DC = 0;
    private int m_iPreviousY_DC = 0;
    
    /** If there was an error decoding, don't pass the error up until reading
     * reaches the end of everything that could be decoded. */
    private IOException m_oFailException = null;
    
    
    /* ---------------------------------------------------------------------- */
    /* Constructors --------------------------------------------------------- */
    /* ---------------------------------------------------------------------- */
    
    
    public StrFrameUncompressorIS(StrFrameDemuxerIS oSFIS) throws IOException {
        
        this(oSFIS, oSFIS.getWidth(), 
                    oSFIS.getHeight());

    }
        
    
    public StrFrameUncompressorIS(InputStream oIS, long lngWidth, 
                                                   long lngHeight) 
        throws IOException 
    {
        
        // New bit reader. Read in 2 bytes at a time (Little-endian)
        m_oBitReader = new BufferedBitReader(oIS, 2);
        
        // Read the stream header information
        
        /* FF7 videos have 40 bytes of camera data at the start of the frame 
         * So we'll keep reading until we find the 0x3800 header...unless
         * those bytes are found in part of the camera data... :/      */
        int iTries = 50; // we'll just try 50 times
        do {
            m_lngNumberOfRunLenthCodes = m_oBitReader.ReadUnsignedBits(16);
            m_lngHeader3800 = m_oBitReader.ReadUnsignedBits(16);
            iTries--;
        } while (m_lngHeader3800 != 0x3800 && iTries > 0);
            
        if (m_lngHeader3800 != 0x3800)
            throw new IOException("0x3800 not found in start of frame");
        
        m_lngQuantizationScaleChrom = 
        m_lngQuantizationScaleLumin = (int)m_oBitReader.ReadUnsignedBits(16);
        m_lngVersion = (int)m_oBitReader.ReadUnsignedBits(16);
        
        // We only can handle version 0, 1, 2, or 3 so far
        if (m_lngVersion < 0 || m_lngVersion > 3)
            throw new IOException("We don't know how to handle version " 
                                   + m_lngVersion);
        
        if (m_lngVersion == 0) { // For Lain...
            // The NumberOfRunLenthCodes 16 bits are actually the quantization
            // scale for Luminance and Crominance
            m_lngQuantizationScaleLumin = m_lngNumberOfRunLenthCodes & 0xFF;
            m_lngQuantizationScaleChrom = (m_lngNumberOfRunLenthCodes >>> 8) & 0xFF;
            
            // Lain also uses an actual byte stream (behaves like Big-Endian)
            // so it's only one byte per read
            m_oBitReader.setBytesPerBuffer(1);
        } 

        
        m_lngWidth = lngWidth;
        m_lngHeight = lngHeight;
        
        // Actual width/height in macroblocks 
        // (since you can't have a partial macroblock)
        long lngActualWidth, lngActualHeight;
        
        if ((m_lngWidth % 16) > 0)
            lngActualWidth = (m_lngWidth / 16 + 1) * 16;
        else
            lngActualWidth = m_lngWidth;
        
        if ((m_lngHeight % 16) > 0)
            lngActualHeight = (m_lngHeight / 16 + 1) * 16;
        else
            lngActualHeight = m_lngHeight;
        
        // Calculate number of macro-blocks in the frame
        long iMacroBlockCount = (lngActualWidth / 16) * (lngActualHeight / 16);

        // We have everything we need
        // now uncompress the entire frame, one macroblock at a time
        try {
            for (int i = 0; i < iMacroBlockCount; i++) {
                if (DebugVerbose >= 3)
                    System.err.println("Decoding macroblock " + i);
                // queues up all read MDEC codes
                UncompressMacroBlock();
            }
        } catch (IOException e) {
            // We failed! :(
            m_oFailException = e; // save the error for later
            if (DebugVerbose >= 3)
                e.printStackTrace();
        }
        
        // Setup for reading by peeking at the first 16 bits
        m_oCurrent16Bits = m_oReaderQueue.peek();
        
    }

    /* ---------------------------------------------------------------------- */
    /* Properties ----------------------------------------------------------- */
    /* ---------------------------------------------------------------------- */
    
    public long getWidth() {
        return m_lngWidth;
    }

    public long getHeight() {
        return m_lngHeight;
    }
        
    public long getFrameVersion() {
        return m_lngVersion;
    }
    
    /** Returns the file position in the underlying stream.
     * implements IGetFilePointer */
    public long getFilePointer() { 
        if (m_oCurrent16Bits != null)
            // returns the position in the original file where
            // the variable length code was read
            return (long)m_oCurrent16Bits.OriginalFilePos;
        else
            return -1;
    }
    
    /** Returns the original bits that were read to make the current
     * variable length code */
    public String getBits() {
        if (m_oCurrent16Bits != null)
            return m_oCurrent16Bits.OriginalVariableLengthCodeBits;
        else
            return null;
    }
    
    /* ---------------------------------------------------------------------- */
    /* Public Functions ----------------------------------------------------- */
    /* ---------------------------------------------------------------------- */
    
    /** extends InputStream */
    public int read() throws IOException {
        
        if (m_oReaderQueue.size() == 0) {
            // We've run out of data...
            // Was there an error during the decoding?
            if (m_oFailException != null)
                throw new // pass on the error
                   IOException("Macro block decoding failed", m_oFailException);
            else
                // Nope, we're just at the end of the data
                return -1;
        }
        
        int iRet;
        
        // Alternate between reading the bottom byte and the top byte
        if (m_blnLowHighReadToggle)
            iRet = (int)(m_oReaderQueue.peek().ToMdecWord() & 0xFF);
        else {
            iRet = (int)((m_oReaderQueue.remove().ToMdecWord() >>> 8) & 0xFF);
            m_oCurrent16Bits = m_oReaderQueue.peek();
        }
        
        m_blnLowHighReadToggle = !m_blnLowHighReadToggle;
        
        return iRet;
    }
    
    /* ---------------------------------------------------------------------- */
    /* Private Functions ---------------------------------------------------- */
    /* ---------------------------------------------------------------------- */
    
    private void UncompressMacroBlock() throws IOException {
        
        if (m_lngVersion == 1 || m_lngVersion == 2 || m_lngVersion == 0) {
            
            // For version 2, all Cr, Cb, Y1, Y2, Y3, Y4 
            // DC Coefficients are encoded the same
            for (String sBlock : new String[] {"Cr","Cb","Y1","Y2","Y3","Y4"}) 
            {
                DecodeV2_DC_ChrominanceOrLuminance(sBlock);
                Decode_AC_Coefficients();
            }
            
        } else if (m_lngVersion == 3 || (m_lngVersion == 0)) {
            
            // For version 3, DC coefficients are encoded differently for
            // DC Chrominance and DC Luminance. 
            // In addition, the value is relative to the previous value.
            // (this is the same way mpeg-1 does it)
            
            // Cr
            m_iPreviousCr_DC = DecodeV3_DC_ChrominanceOrLuminance(
                                m_iPreviousCr_DC, 
                                DC_CHROMINANCE_LONGEST_VARIABLE_LENGTH_CODE, 
                                DC_Chrominance_VariableLengthCodes, 
                                "Cr");
            Decode_AC_Coefficients();

            // Cb
            m_iPreviousCb_DC = DecodeV3_DC_ChrominanceOrLuminance(
                                m_iPreviousCb_DC, 
                                DC_CHROMINANCE_LONGEST_VARIABLE_LENGTH_CODE, 
                                DC_Chrominance_VariableLengthCodes, 
                                "Cb");
            Decode_AC_Coefficients();
            
            // Y1, Y2, Y3, Y4
            for (String sBlock : new String[] {"Y1", "Y2", "Y3", "Y4"}) {
                m_iPreviousY_DC = DecodeV3_DC_ChrominanceOrLuminance(
                                    m_iPreviousY_DC, 
                                    DC_LUMINANCE_LONGEST_VARIABLE_LENGTH_CODE, 
                                    DC_Luminance_VariableLengthCodes, 
                                    sBlock);
                Decode_AC_Coefficients();
            }
            
        } else {
            throw new IOException("Unhandled version " + m_lngVersion);
        }
        
    }
    
    /** Decodes the DC Coefficient at the start of every block (for v.2) and
     *  adds the resulting MDEC code to the queue.
     *  DC coefficients are stored the same way for both Chrominance and
     *  Luminance in version 2 frames (althouogh Lain has a minor tweak). */
    private void DecodeV2_DC_ChrominanceOrLuminance(String sBlock) 
        throws IOException 
    {
        StrFrameMDEC.Mdec16Bits oDCChrominanceOrLuminance = 
                new StrFrameMDEC.Mdec16Bits();
        
        // Save the current file position
        oDCChrominanceOrLuminance.OriginalFilePos = m_oBitReader.getPosition();
        
        // Save the bits that make up the DC coefficient
        oDCChrominanceOrLuminance.OriginalVariableLengthCodeBits = 
                                              m_oBitReader.PeekBitsToString(10);
        // Now read the coefficient value
        oDCChrominanceOrLuminance.Bottom10Bits = 
                                           (int)m_oBitReader.ReadSignedBits(10);
        if (m_lngVersion != 0)
            
            // The bottom 10 bits now hold the DC Coefficient for the block,
            // now squeeze the frame's quantization scale into the top 6 bits
            oDCChrominanceOrLuminance.Top6Bits = 
                                        (int)(m_lngQuantizationScaleChrom & 63);
        
        else {
            // Lain uses different values for the 
            // chorminance and luminance DC coefficients
            if (sBlock.startsWith("Y")) // if luminance
                oDCChrominanceOrLuminance.Top6Bits = 
                                        (int)(m_lngQuantizationScaleLumin & 63);
            else
                oDCChrominanceOrLuminance.Top6Bits = 
                                        (int)(m_lngQuantizationScaleChrom & 63);

        }
        
        if (DebugVerbose >= 7)
            System.err.println(String.format(
                    "%1.3f: %s -> %s DC coefficient %d",
                    m_oBitReader.getPosition(),
                    oDCChrominanceOrLuminance.OriginalVariableLengthCodeBits,
                    sBlock,
                    oDCChrominanceOrLuminance.Bottom10Bits));
        
        // Add the MDEC code to the queue
        m_oReaderQueue.offer(oDCChrominanceOrLuminance);
    }
    
    
    /** Decodes the DC Coefficient at the start of every block (for v.3) and
     *  adds the resulting MDEC code to the queue.
     *  DC coefficients are stored differently for Chrominance and
     *  Luminance in version 3 frames. The arguments to this function
     *  take care of all the differences. */
    private int DecodeV3_DC_ChrominanceOrLuminance(
                         int iPreviousDC, 
                         int iLongestVariableLengthCode, 
                         DCVariableLengthCode DCVariableLengthCodeTable[],
                         String sBlockName) 
        throws IOException 
    {
        StrFrameMDEC.Mdec16Bits oDCChrominanceOrLuminance = 
                new StrFrameMDEC.Mdec16Bits();
        
        // Save the current file position
        oDCChrominanceOrLuminance.OriginalFilePos = m_oBitReader.getPosition();
        
        // Peek enough bits
        String sBits = 
                m_oBitReader.PeekBitsToString(iLongestVariableLengthCode);
        boolean blnFoundCode = false;

        // Search though all the DC Coefficient codes for a match
        for (DCVariableLengthCode tDcVlc : DCVariableLengthCodeTable) {

            if (sBits.startsWith(tDcVlc.VariableLengthCode)) { // match?

                // Save the matching code, then skip past those bits
                oDCChrominanceOrLuminance.OriginalVariableLengthCodeBits = 
                        tDcVlc.VariableLengthCode;
                m_oBitReader.SkipBits(tDcVlc.VariableLengthCode.length());

                if (tDcVlc.DC_Length == 0) {

                    oDCChrominanceOrLuminance.Bottom10Bits = 0;

                } else {

                    // Save the additional bits
                    oDCChrominanceOrLuminance.OriginalVariableLengthCodeBits += 
                            m_oBitReader.PeekBitsToString(tDcVlc.DC_Length);
                    
                    // Read the DC differential
                    int iDC_Differential = 
                           (int)m_oBitReader.ReadUnsignedBits(tDcVlc.DC_Length);
                    // Lookup its value
                    oDCChrominanceOrLuminance.Bottom10Bits = 
                            tDcVlc.DC_DifferentialLookup[iDC_Differential];
                    
                    // PSX seems to multiply it by 4 for no reason???
                    oDCChrominanceOrLuminance.Bottom10Bits *= 4; 
                }
                
                // Now adjust the DC Coefficent with the previous coefficient
                oDCChrominanceOrLuminance.Bottom10Bits += iPreviousDC;
                blnFoundCode = true; // we found the code
                break; // we're done
            }
        } 
        if (!blnFoundCode) 
            throw new IOException("Unknown DC " + sBlockName + 
                                  " variable length code " + sBits);
        
        // The bottom 10 bits now hold the DC Coefficient for the block,
        // now squeeze the frame's quantization scale into the top 6 bits
        oDCChrominanceOrLuminance.Top6Bits = 
                (int)(m_lngQuantizationScaleChrom & 63);
        
        if (DebugVerbose >= 7)
            System.err.println(String.format(
                    "%d: %s -> %s DC coefficient %d",
                    m_oBitReader.getPosition(), 
                    oDCChrominanceOrLuminance.OriginalVariableLengthCodeBits,
                    sBlockName,
                    oDCChrominanceOrLuminance.Bottom10Bits));
        
        // Add the MDEC code to the queue
        m_oReaderQueue.offer(oDCChrominanceOrLuminance);
        
        // return the new DC Coefficient
        return oDCChrominanceOrLuminance.Bottom10Bits;
    }
    
    
    /** Decodes all the block's AC Coefficients in the stream, 
     * and adds the resulting MDEC codes to the queue.
     * AC Coefficients are decoded the same for version 2 and 3 frames */
    private void Decode_AC_Coefficients() throws IOException {
        StrFrameMDEC.Mdec16Bits oRlc;
        int iTotalRunLength = 0;
        double dblFilePos;
        
        while (true) {
            // First save the current file position
            dblFilePos = m_oBitReader.getPosition();
            
            // Decode the next bunch of bits into an MDEC run length code
            oRlc = Decode_AC_Code();
            // Add the saved file position
            oRlc.OriginalFilePos = dblFilePos;
            
            // Add the MDEC code to the queue
            m_oReaderQueue.offer(oRlc);
            
            if (DebugVerbose >= 7)
                System.err.print(String.format(
                        "%1.3f: %s -> ",
                        m_oBitReader.getPosition(),
                        oRlc.OriginalVariableLengthCodeBits));
            
            // Did we hit the end of the block?
            if (oRlc.ToMdecWord() == StrFrameMDEC.MDEC_END_OF_BLOCK) {
                if (DebugVerbose >= 7)
                    System.err.println("EOB");
                
                // Then we're done here
                break;
            } else {
                if (DebugVerbose >= 7)
                    System.err.println(
                        "(" + oRlc.Top6Bits + ", " + oRlc.Bottom10Bits + ")");
            }
            
            // Add this run length code to the total
            iTotalRunLength += oRlc.Top6Bits + 1;
            
            // Hopefully we haven't gone over
            if (iTotalRunLength > 63) {
                throw new IOException(
                        "Run length out of bounds: " + (iTotalRunLength + 1) + " at " + m_oBitReader.getPosition());
            }
        } 
    }
    
    
    /** Decodes the next AC Coefficient bits in the stream and returns the
     *  resulting MDEC code. */
    private StrFrameMDEC.Mdec16Bits Decode_AC_Code() throws IOException {
        
        // Peek at the upcoming bits
        String sBits = 
                m_oBitReader.PeekBitsToString(AC_LONGEST_VARIABLE_LENGTH_CODE);
        
        if (sBits.startsWith(AC_ESCAPE_CODE)) { // Is it the escape code?
            
            return Decode_AC_EscapeCode();
            
        } else if (sBits.startsWith(VLC_END_OF_BLOCK)) { // end of block?
            
            m_oBitReader.SkipBits(VLC_END_OF_BLOCK.length());
            StrFrameMDEC.Mdec16Bits tRlc = 
                    new StrFrameMDEC.Mdec16Bits(StrFrameMDEC.MDEC_END_OF_BLOCK);
            tRlc.OriginalVariableLengthCodeBits = VLC_END_OF_BLOCK;
            return tRlc;
            
        } else { // must be a normal code
            
            return Decode_AC_VariableLengthCode(sBits);
            
        }
            
    }

    /** Decodes the AC Escape Code bits from the stream and returns the
     *  resulting MDEC code. */
    private StrFrameMDEC.Mdec16Bits Decode_AC_EscapeCode() throws IOException {
        StrFrameMDEC.Mdec16Bits tRlc = new StrFrameMDEC.Mdec16Bits();
        
        // Save the escape code bits, then skip them in the stream
        tRlc.OriginalVariableLengthCodeBits = AC_ESCAPE_CODE + "+";
        m_oBitReader.SkipBits(AC_ESCAPE_CODE.length());
        
        // Read 6 bits for the run of zeros
        tRlc.OriginalVariableLengthCodeBits += 
                m_oBitReader.PeekBitsToString(6) + "+";
        tRlc.Top6Bits = (int)m_oBitReader.ReadUnsignedBits(6);

        if (m_lngVersion == 1 || m_lngVersion == 2 || m_lngVersion == 3) {
            // Normal playstation encoding stores the escape code in 16 bits:
            // 6 for run of zeros (already read), 10 for AC Coefficient
            
            // Read the 10 bits of AC Coefficient
            tRlc.OriginalVariableLengthCodeBits += 
                    m_oBitReader.PeekBitsToString(10);
            tRlc.Bottom10Bits = (int)m_oBitReader.ReadSignedBits(10);

            // Did we end up with an AC coefficient of zero?
            if (tRlc.Bottom10Bits == 0) {
                // Normally this is concidered an error
                // but FF7 has these pointless codes. So we'll only allow it
                // if this is FF7
                if (m_lngVersion != 1) // If not FF7, throw an error
                    throw new IOException(
                            "AC Escape code: Run length is zero at " 
                            + m_oBitReader.getPosition());
            }
            
        } else if (m_lngVersion == 0) { // Lain
            
            /* Lain playstation uses mpeg1 specification escape code
            Fixed Length Code       Level 
            forbidden               -256  
            1000 0000 0000 0001     -255  
            1000 0000 0000 0010     -254  
            ...                          
            1000 0000 0111 1111     -129  
            1000 0000 1000 0000     -128  
            1000 0001               -127  
            1000 0010               -126  
            ...                           
            1111 1110               -2    
            1111 1111               -1    
            forbidden                0    
            0000 0001                1    
            0000 0010                2    
            ...   
            0111 1110               126   
            0111 1111               127   
            0000 0000 1000 0000     128   
            0000 0000 1000 0001     129   
            ...   
            0000 0000 1111 1110     254   
            0000 0000 1111 1111     255   
             */
            // Peek at the first 8 bits
            String sBits = m_oBitReader.PeekBitsToString(8);
            tRlc.OriginalVariableLengthCodeBits += sBits;
            if (sBits.equals("00000000")) {
                // If it's the special 00000000
                // Positive
                m_oBitReader.SkipBits(8);
                tRlc.OriginalVariableLengthCodeBits += "+" + m_oBitReader.PeekBitsToString(8);
                tRlc.Bottom10Bits = (int)m_oBitReader.ReadUnsignedBits(8);
                
            } else if (sBits.equals("10000000")) {
                // If it's the special 10000000
                // Negitive
                m_oBitReader.SkipBits(8);
                tRlc.OriginalVariableLengthCodeBits += "+" + m_oBitReader.PeekBitsToString(8);
                tRlc.Bottom10Bits = -256 + (int)m_oBitReader.ReadUnsignedBits(8);
                
            } else {
                // Otherwise we already have the value
                tRlc.Bottom10Bits = (int)m_oBitReader.ReadSignedBits(8);
            }
            
        } else {
            // unknown version
            throw new IOException("Unhandled version " + m_lngVersion);
        }
        
        return tRlc;
    }
    
    /** Decodes sBits into an AC Coefficient and skips the bits in the 
     *  stream */
    private StrFrameMDEC.Mdec16Bits Decode_AC_VariableLengthCode(String sBits) 
        throws IOException 
    {
        StrFrameMDEC.Mdec16Bits tRlc = new StrFrameMDEC.Mdec16Bits();
        boolean blnFoundCode = false;
        ACVariableLengthCode tVarLenCodes[];
        
        // Use the correct AC variable length code list
        if (m_lngVersion != 0) {
            tVarLenCodes = AC_VARIABLE_LENGTH_CODES_MPEG1;
        } else {
            tVarLenCodes = AC_VARIABLE_LENGTH_CODES_LAIN;
        }
        
        // Search through the list to find the matching AC variable length code
        for (ACVariableLengthCode vlc : tVarLenCodes) {
            if (sBits.startsWith(vlc.VariableLengthCode)) {
                
                // Yay we found it!
                // Skip that many bits
                m_oBitReader.SkipBits(vlc.VariableLengthCode.length());
                
                // Save the resulting code, and run of zeros
                tRlc.OriginalVariableLengthCodeBits = vlc.VariableLengthCode;
                tRlc.Top6Bits = vlc.RunOfZeros;
                // Take either the positive or negitive AC coefficient,
                // depending on the sign bit
                if (m_oBitReader.ReadUnsignedBits(1) == 1) {
                    // negitive
                    tRlc.Bottom10Bits = -vlc.AbsoluteLevel;
                    tRlc.OriginalVariableLengthCodeBits += "+1";
                } else {
                    // positive
                    tRlc.Bottom10Bits = vlc.AbsoluteLevel;
                    tRlc.OriginalVariableLengthCodeBits += "+0";
                }
                
                blnFoundCode = true;
                break;
            }
        }
        
        if (! blnFoundCode) {
            throw new IOException("Unmatched AC variable length code: " +
                                  sBits + " at " + m_oBitReader.getPosition());
        }
        
        return tRlc;
    }

}