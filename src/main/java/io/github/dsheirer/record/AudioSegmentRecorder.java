/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.record;

import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.convert.MP3AudioConverter;
import io.github.dsheirer.identifier.*;
import io.github.dsheirer.identifier.configuration.*;
import io.github.dsheirer.record.wave.AudioMetadata;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import io.github.dsheirer.record.wave.WaveWriter;
import io.github.dsheirer.sample.ConversionUtils;
import org.apache.commons.io.FilenameUtils;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/**
 * Recording utility for audio segments
 */
public class AudioSegmentRecorder
{
    private final static Logger mLog = LoggerFactory.getLogger(AudioSegmentRecorder.class);

    public static final int MP3_BIT_RATE = 16;
    public static final boolean CONSTANT_BIT_RATE = false;

    /**
     * Records the audio segment to the specified path using the specified recording format
     * @param audioSegment to record
     * @param path for the recording
     * @param recordFormat to use (WAVE, MP3)
     * @throws IOException on any errors
     */
    public static void record(AudioSegment audioSegment, Path path, RecordFormat recordFormat) throws IOException
    {
        switch(recordFormat)
        {
            case MP3:
                recordMP3(audioSegment, path);
                break;
            case WAVE:
                recordWAVE(audioSegment, path);
                break;
            default:
                throw new IllegalArgumentException("Unrecognized recording format [" + recordFormat.name() + "]");
        }
    }
    public static void writeMetaJson(AudioSegment audioSegment, Path path) {
        IdentifierCollection identifierCollection = audioSegment.getIdentifierCollection();

        Identifier system = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);
        String systemString = ((SystemConfigurationIdentifier) system).getValue();

        Identifier site = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.SITE, Role.ANY);
        String siteString = ((SiteConfigurationIdentifier) site).getValue();

        Identifier channel = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY);
        String channelString = ((ChannelNameConfigurationIdentifier) channel).getValue();

        Identifier decoder = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.DECODER_TYPE,
                Role.ANY);
        String decoderString = ((DecoderTypeConfigurationIdentifier) decoder).getValue().getDisplayString();

        Identifier frequency = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION,
                Form.CHANNEL_FREQUENCY, Role.ANY);
        String frequencyString = ((FrequencyConfigurationIdentifier) frequency).getValue() + "";

        ArrayList<String> talkgroups = new ArrayList<>();
        ArrayList<String> units = new ArrayList<>();

        boolean tones = false;


        for (Identifier to : identifierCollection.getIdentifiers(Role.TO)) {
            talkgroups.add(to.toString());
            break;
        }
//        Identifier from = identifierCollection.getIdentifier(IdentifierClass.USER, Form.TONE, Role.FROM);



        for (Identifier from : identifierCollection.getIdentifiers(Role.FROM)) {
            units.add(from.toString());
            break;
        }


        for (Identifier identifier : identifierCollection.getIdentifiers(Role.FROM)) {
            if (identifier.getForm() == Form.TONE) {
                tones = true;
                break;
            }
        }

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("system", systemString);
        jsonObject.put("site", siteString);
        jsonObject.put("channel", channelString);
        jsonObject.put("decoder", decoderString);
        jsonObject.put("frequency", frequencyString);
        jsonObject.put("talkgroups", talkgroups.size() > 0 ? talkgroups.get(0) : "-1");
        jsonObject.put("units", units.size() > 0 ? units.get(0) : "-1");
        jsonObject.put("date", System.currentTimeMillis());
        jsonObject.put("tones", tones);


        try {
            FileWriter file = new FileWriter(FilenameUtils.removeExtension(path.toString()) + ".json");
            file.write(jsonObject.toJSONString());
            file.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Records the audio segment as an MP3 file to the specified path.
     * @param audioSegment to record
     * @param path for the recording
     * @throws IOException on any errors
     */
    public static void recordMP3(AudioSegment audioSegment, Path path) throws IOException
    {
        if(audioSegment.hasAudio())
        {
            writeMetaJson(audioSegment, path);
            OutputStream outputStream = new FileOutputStream(path.toFile());

            //Write ID3 metadata
            Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(audioSegment.getIdentifierCollection(),
                audioSegment.getAliasList());

            byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap);
            outputStream.write(id3Bytes);

            //Convert audio to MP3 and write to file
            MP3AudioConverter converter = new MP3AudioConverter(MP3_BIT_RATE, CONSTANT_BIT_RATE);
            byte[] mp3 = converter.convertAudio(audioSegment.getAudioBuffers());
            outputStream.write(mp3);

            byte[] lastFrame = converter.flush();

            if(lastFrame != null && lastFrame.length > 0)
            {
                outputStream.write(lastFrame);
            }

            outputStream.flush();
            outputStream.close();
        }
    }

    /**
     * Records the audio segment as a WAVe file to the specified path.
     * @param audioSegment to record
     * @param path for the recording
     * @throws IOException on any errors
     */
    public static void recordWAVE(AudioSegment audioSegment, Path path) throws IOException
    {
        if(audioSegment.hasAudio())
        {
            WaveWriter writer = new WaveWriter(AudioFormats.PCM_SIGNED_8KHZ_16BITS_MONO, path);

            for(float[] audioBuffer: audioSegment.getAudioBuffers())
            {
                writer.writeData(ConversionUtils.convertToSigned16BitSamples(audioBuffer));
            }

            Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(audioSegment.getIdentifierCollection(),
                audioSegment.getAliasList());

            ByteBuffer listChunk = AudioMetadataUtils.getLISTChunk(metadataMap);
            byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap);
            ByteBuffer id3Chunk = AudioMetadataUtils.getID3Chunk(id3Bytes);
            writer.writeMetadata(listChunk, id3Chunk);
            writer.close();
        }
    }
}
