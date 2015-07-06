/*
 * jPSXdec: PlayStation 1 Media Decoder/Converter in Java
 * Copyright (C) 2007-2014  Michael Sabin
 * All rights reserved.
 *
 * Redistribution and use of the jPSXdec code or any derivative works are
 * permitted provided that the following conditions are met:
 *
 *  * Redistributions may not be sold, nor may they be used in commercial
 *    or revenue-generating business activities.
 *
 *  * Redistributions that are modified from the original source must
 *    include the complete source code, including the source code for all
 *    components used by a binary built from the modified sources. However, as
 *    a special exception, the source code distributed need not include
 *    anything that is normally distributed (in either source or binary form)
 *    with the major components (compiler, kernel, and so on) of the operating
 *    system on which the executable runs, unless that component itself
 *    accompanies the executable.
 *
 *  * Redistributions must reproduce the above copyright notice, this list
 *    of conditions and the following disclaimer in the documentation and/or
 *    other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jpsxdec.discitems;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import jpsxdec.I18N;
import jpsxdec.LocalizedMessage;
import jpsxdec.audio.SquareAdpcmDecoder;
import jpsxdec.sectors.ISquareAudioSector;
import jpsxdec.sectors.IdentifiedSector;
import jpsxdec.util.ExposedBAOS;
import jpsxdec.util.NotThisTypeException;

/** Represents a series of Square ADPCM sectors that combine to make an audio stream.
 * These kinds of streams are found in Final Fantasy 8, 9, and Chrono Cross.  */
public class DiscItemSquareAudioStream extends DiscItemAudioStream {

    private static final Logger LOG = Logger.getLogger(DiscItemSquareAudioStream.class.getName());

    public static final String TYPE_ID = "SquareAudio";

    private static final String SAMPLE_COUNT_LEFT_KEY = "Left samples";
    private final long _lngLeftSampleCount;
    
    private static final String SAMPLE_COUNT_RIGHT_KEY = "Right samples";
    private final long _lngRightSampleCount;
    
    private static final String SAMPLES_PER_SEC_KEY = "Samples/Sec";
    private final int _iSamplesPerSecond;

    private static final String SECTORS_PAST_END_KEY = "Sectors past end";
    private final int _iSectorsPastEnd;
    
    public DiscItemSquareAudioStream(int iStartSector, int iEndSector,
            long lngLeftSampleCount, long lngRightSampleCount, 
            int iSamplesPerSecond, int iSectorsPastEnd)
    {
        super(iStartSector, iEndSector);

        _lngLeftSampleCount = lngLeftSampleCount;
        _lngRightSampleCount = lngRightSampleCount;
        _iSamplesPerSecond = iSamplesPerSecond;
        _iSectorsPastEnd = iSectorsPastEnd;

        if (_lngLeftSampleCount != _lngRightSampleCount)
            LOG.log(Level.WARNING, "Left & right sample count does not match: {0,number,#} != {1,number,#}",
                    new Object[]{_lngLeftSampleCount, _lngRightSampleCount});
    }
    
    public DiscItemSquareAudioStream(SerializedDiscItem fields) throws NotThisTypeException
    {
        super(fields);
        
        _iSamplesPerSecond = fields.getInt(SAMPLES_PER_SEC_KEY);
        _lngLeftSampleCount = fields.getLong(SAMPLE_COUNT_LEFT_KEY);
        _lngRightSampleCount = fields.getLong(SAMPLE_COUNT_RIGHT_KEY);
        _iSectorsPastEnd = fields.getInt(SECTORS_PAST_END_KEY);

        if (_lngLeftSampleCount != _lngRightSampleCount)
            LOG.log(Level.WARNING, "Left & right sample count does not match: {0,number,#} != {1,number,#}",
                    new Object[]{_lngLeftSampleCount, _lngRightSampleCount});
    }
    
    @Override
    public SerializedDiscItem serialize() {
        SerializedDiscItem fields = super.serialize();
        fields.addNumber(SAMPLES_PER_SEC_KEY, _iSamplesPerSecond);
        fields.addNumber(SAMPLE_COUNT_LEFT_KEY, _lngLeftSampleCount);
        fields.addNumber(SAMPLE_COUNT_RIGHT_KEY, _lngRightSampleCount);
        fields.addNumber(SECTORS_PAST_END_KEY, _iSectorsPastEnd);
        return fields;
    }

    public String getSerializationTypeId() {
        return TYPE_ID;
    }

    public boolean isStereo() {
        return true;
    }

    public int getSectorsPastEnd() {
        return _iSectorsPastEnd;
    }

    public int getDiscSpeed() {
        return 2;
    }

    @Override
    public int getPresentationStartSector() {
        return getStartSector() + 1;
    }

    @Override
    public double getApproxDuration() {
        long lngSampleCount = _lngLeftSampleCount > _lngRightSampleCount ?
                              _lngLeftSampleCount : _lngRightSampleCount;
        return lngSampleCount / (double)_iSamplesPerSecond;
    }

    public AudioFormat getAudioFormat(boolean blnBigEndian) {
        return new AudioFormat(_iSamplesPerSecond, 16, 2, true, blnBigEndian);
    }

    public int getSampleRate() {
        return _iSamplesPerSecond;
    }

    @Override
    public LocalizedMessage getInterestingDescription() {
        long lngSampleCount = _lngLeftSampleCount > _lngRightSampleCount ?
                              _lngLeftSampleCount : _lngRightSampleCount;
        // unable to find ANY sources of info about how to localize durations
        Date secs = new Date(0, 0, 0, 0, 0, (int)Math.max(lngSampleCount / _iSamplesPerSecond, 1));
        return new LocalizedMessage("{0,time,m:ss}, {1,number,#} Hz Stereo", // I18N
                                    secs, _iSamplesPerSecond);
    }

    public ISectorAudioDecoder makeDecoder(double dblVolume) {
        return new SquareConverter(new SquareAdpcmDecoder(dblVolume));
    }

    // -------------------------------------------------------------------------

    private class SquareConverter implements ISectorAudioDecoder {

        private final SquareAdpcmDecoder __decoder;
        private final AudioFormat __format;
        private final ExposedBAOS __tempBuffer;
        private ISectorTimedAudioWriter __audioWriter;
        private ISquareAudioSector __leftAudioSector, __rightAudioSector;

        public SquareConverter(SquareAdpcmDecoder decoder) {
            __decoder = decoder;
            // 1840 is the most # of ADPCM bytes found in any Square game sector (FF9)
            __tempBuffer = new ExposedBAOS(SquareAdpcmDecoder.calculateOutputBufferSize(1840));
            __format = __decoder.getOutputFormat(_iSamplesPerSecond);
        }

        public void setAudioListener(ISectorTimedAudioWriter audioOut) {
            __audioWriter = audioOut;
        }

        public boolean feedSector(IdentifiedSector sector, Logger log) throws IOException {
            if (!(sector instanceof ISquareAudioSector))
                return false;

            ISquareAudioSector audSector = (ISquareAudioSector) sector;
            if (audSector.getAudioChannel() == 0) {
                __leftAudioSector = audSector;
            } else if (audSector.getAudioChannel() == 1) {
                __rightAudioSector = audSector;

                if (__leftAudioSector.getAudioDataSize() != __rightAudioSector.getAudioDataSize() ||
                    __leftAudioSector.getSamplesPerSecond() != __rightAudioSector.getSamplesPerSecond())
                    throw new RuntimeException("Left/Right audio does not match.");

                __tempBuffer.reset();
                int iSize = __decoder.decode(__leftAudioSector.getIdentifiedUserDataStream(),
                        __rightAudioSector.getIdentifiedUserDataStream(),
                        __rightAudioSector.getAudioDataSize(), __tempBuffer,
                        log);
                __audioWriter.write(__format, __tempBuffer.getBuffer(), 0, __tempBuffer.size(), __rightAudioSector.getSectorNumber());
            } else {
                throw new RuntimeException("Invalid audio channel " + audSector.getAudioChannel());
            }
            return true;
        }

        public double getVolume() {
            return __decoder.getVolume();
        }

        public void setVolume(double dblVolume) {
            __decoder.setVolume(dblVolume);
        }

        public AudioFormat getOutputFormat() {
            return __format;
        }

        public int getSamplesPerSecond() {
            return _iSamplesPerSecond;
        }

        public int getDiscSpeed() {
            return 2;
        }

        public void reset() {
            __decoder.resetContext();
        }

        public int getEndSector() {
            return DiscItemSquareAudioStream.this.getEndSector();
        }

        public int getStartSector() {
            return DiscItemSquareAudioStream.this.getStartSector();
        }

        public int getPresentationStartSector() {
            return DiscItemSquareAudioStream.this.getPresentationStartSector();
        }

        public String[] getAudioDetails() {
            return new String[] { DiscItemSquareAudioStream.this.toString() };
        }
    }

}