/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*                2017  jflesch <jflesch@kwain.net>
*                2017  Martin Nowack
*                2017  linuxlurak with help of Dododappere, see: https://github.com/oliexdev/openScale/issues/111
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/
package com.health.openscale.core.bluetooth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.util.Log;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.TreeSet;
import java.util.UUID;

public class BluetoothBeurerBF700_800 extends BluetoothCommunication {
    public final static String TAG = "BEURER BF700/800";

    private static final int PRIMARY_SERVICE = 0x180A;
    private static final UUID SYSTEM_ID = UUID.fromString("00002A23-0000-1000-8000-00805F9B34FB");
    private static final UUID MODEL_NUMBER_STRING =
            UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB");
    private static final UUID SERIAL_NUMBER_STRING =
            UUID.fromString("00002A25-0000-1000-8000-00805F9B34FB");
    private static final UUID FIRMWARE_REVISION_STRING =
            UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB");
    private static final UUID HARDWARE_REVISION_STRING =
            UUID.fromString("00002A27-0000-1000-8000-00805F9B34FB");
    private static final UUID SOFTWARE_REVISION_STRING =
            UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB");
    private static final UUID MANUFACTURER_NAME_STRING =
            UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB");
    //nachfolgend nicht geprüft
    private static final UUID IEEE_11073_20601_REGULATORY_CERTIFICATION_DATA_LIST =
            UUID.fromString("00002A2A-0000-1000-8000-00805F9B34FB");
    //nachfolgend nicht geprüft
    private static final UUID PNP_ID =
            UUID.fromString("00002A50-0000-1000-8000-00805F9B34FB");

    private static final UUID DEVICE_NAME =
            UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB");
    private static final UUID APPEARANCE =
            UUID.fromString("00002A01-0000-1000-8000-00805F9B34FB");
    //nachfolgend nicht geprüft
    private static final UUID PERIPHERICAL_PRIVACY_FLAG =
            UUID.fromString("00002A02-0000-1000-8000-00805F9B34FB");
    //nachfolgend nicht geprüft
    private static final UUID RECONNECTION_ADDRESS =
            UUID.fromString("00002A03-0000-1000-8000-00805F9B34FB");
    //nachfolgend nicht geprüft
    private static final UUID PERIPHERICAL_PREFERRED_CONNECTION_PARAMETERS =
            UUID.fromString("00002A04-0000-1000-8000-00805F9B34FB");

    private static final UUID GENERIC_ATTRIBUTE =
            UUID.fromString("00001801-0000-1000-8000-00805F9B34FB");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB");

    // descriptor ; handle = 0x000f  <-- kommentar nicht geprüft
    //unterschied zur sanitas (00002901) hier bei Beurer BF700 00002902
    //2902 = Client Characteristic Configuration
    //2901 = Characteristic User Description
    //see https://www.bluetooth.com/specifications/gatt/descriptors
    private static final UUID CLIENT_CHARACTERISTICS_CONFIGURATION =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    //service mit gleicher uuid exisitert
    //aus com.beurer.connect.healthmanager_2017-07-06_source_from_JADX\com\ilink\bleapi\SupportedServices.java:
    //     public static final String SCALE_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb";
    private static final UUID CUSTOM_SERVICE_1 =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    //characteristic FFE2 existiert mit notify, read, write, write no response
    private static final UUID CUSTOM_CHARACTERISTIC_2 = // read-only
            UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB");
    //characteristic FFE1 existiert mit notify, read, write, write no response
    //aus com.beurer.connect.healthmanager_2017-07-06_source_from_JADX\com\ilink\bleapi\SupportedServices.java:
    //    public static final String SCALE_CHARACTERISTIC_UUID_1 = "0000ffe1-0000-1000-8000-00805f9b34fb";
    //    public static final String SCALE_CHARACTERISTIC_UUID_2 = "0000ffe2-0000-1000-8000-00805f9b34fb";
    private static final UUID CUSTOM_CHARACTERISTIC_WEIGHT = // write-only, notify ; handle=0x002e
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");

    private int startByte;
    private int currentScaleUserId;
    private int countRegisteredScaleUsers;
    private TreeSet<Integer> seenUsers;
    private int maxRegisteredScaleUser;
    private ByteArrayOutputStream receivedScaleData;

    private int getAlternativeStartByte(int id) {
        return (startByte & 0xF0) | (id & 0x0F);
    }

    public BluetoothBeurerBF700_800(Context context, int startByte) {
        super(context);
        this.startByte = startByte;
    }

    @Override
    public String deviceName() {
        return "Beurer BF700/710/800 / Runtastic Libra";
    }

    @Override
    boolean nextInitCmd(int stateNr) {

        switch (stateNr) {
            case 0:
                // Initialize data
                currentScaleUserId = -1;
                countRegisteredScaleUsers = -1;
                maxRegisteredScaleUser = -1;
                seenUsers = new TreeSet<>();

                // Setup notification
                setNotificationOn(CUSTOM_SERVICE_1, CUSTOM_CHARACTERISTIC_WEIGHT, CLIENT_CHARACTERISTICS_CONFIGURATION);
                break;
            case 1:
                // Say "Hello" to the scale
                writeBytes(new byte[]{(byte) getAlternativeStartByte(6), (byte) 0x01});
                break;
            case 2:
                // Wait for "Hello" ack from scale
                break;
            case 3:
                // Update timestamp of the scale
                updateDateTimeBeurer();
                break;
            case 4:
                // Set measurement unit
                setUnitCommand();
                break;
            case 5:
                // Request general user information
                writeBytes(new byte[]{(byte) startByte, (byte) 0x33});
                break;
            case 6:
                // Wait for ack of all users
                if (seenUsers.size() < countRegisteredScaleUsers || (countRegisteredScaleUsers == -1)) {
                    // Request this state again
                    setNextCmd(stateNr);
                    break;
                }

                // Got all user acks

                // Check if not found/unknown
                if (currentScaleUserId == 0) {
                    // Unknown user, request creation of new user
                    if (countRegisteredScaleUsers == maxRegisteredScaleUser) {
                        setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE);
                        Log.d(TAG, "Cannot create additional scale user");
                        sendMessage(R.string.error_max_scale_users, 0);
                        break;
                    }

                    // Request creation of user
                    final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

                    // We can only use up to 3 characters and have to handle them uppercase
                    int maxIdx = Math.min(3, selectedUser.getUserName().length());
                    byte[] nick = selectedUser.getUserName().toUpperCase().substring(0, maxIdx).getBytes();

                    byte activity = 2; // activity level: 1 - 5
                    Log.d(TAG, "Create User:" + selectedUser.getUserName());

                    writeBytes(new byte[]{
                            (byte) startByte, (byte) 0x31, (byte) 0x0, (byte) 0x0, (byte) 0x0,
                            (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) 0x0,
                            (byte) (seenUsers.size() > 0 ? Collections.max(seenUsers) + 1 : 101),
                            nick[0], nick[1], nick[2],
                            (byte) selectedUser.getBirthday().getYear(),
                            (byte) selectedUser.getBirthday().getMonth(),
                            (byte) selectedUser.getBirthday().getDate(),
                            (byte) selectedUser.getBodyHeight(),
                            (byte) (((selectedUser.getGender().isMale() ? 1 : 0) << 7) | activity)
                    });
                } else {
                    // Get existing user information
                    Log.d(TAG, "Request getUserInfo " + currentScaleUserId);
                    writeBytes(new byte[]{
                            (byte) startByte, (byte) 0x36, (byte) 0x0, (byte) 0x0, (byte) 0x0,
                            (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) currentScaleUserId
                    });

                }
                Log.d(TAG, "scaleuserid:" + currentScaleUserId + " registered users: " + countRegisteredScaleUsers +
                        " extracted users: " + seenUsers.size());
                break;
            case 7:
                break;
            default:
                // Finish init if everything is done
                return false;
        }

        return true;
    }

    @Override
    boolean nextBluetoothCmd(int stateNr) {

        switch (stateNr) {
            case 0:
                // If no specific user selected
                if (currentScaleUserId == 0)
                    break;

                Log.d(TAG, "Request Saved User Measurements");
                writeBytes(new byte[]{
                        (byte) startByte, (byte) 0x41, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, (byte) currentScaleUserId
                });

                break;
            case 1:
                // Wait for user measurements to be received
                setNextCmd(stateNr);
                break;
            case 2:
                setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE);
                break;
            default:
                return false;

        }
        return true;
    }

    @Override
    boolean nextCleanUpCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                // Force disconnect
                writeBytes(new byte[]{(byte) 0xea, (byte) 0x02});
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        byte[] data = gattCharacteristic.getValue();
        if (data.length == 0)
            return;

        if ((data[0] & 0xFF) == getAlternativeStartByte(6) && (data[1] & 0xFF) == 0x00) {
            Log.d(TAG, "ACK Scale is ready");
            nextMachineStateStep();
            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0xf0 && data[2] == 0x33) {
            Log.d(TAG, "ACK Got general user information");

            int count = (byte) (data[4] & 0xFF);
            int maxUsers = (byte) (data[5] & 0xFF);
            Log.d(TAG, "Count:" + count + " maxUsers:" + maxUsers);

            countRegisteredScaleUsers = count;
            // Check if any scale user is registered
            if (count == 0) {
                currentScaleUserId = 0; // Unknown user
            }
            maxRegisteredScaleUser = maxUsers;

            nextMachineStateStep();
            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0x34) {
            Log.d(TAG, "Ack Get UUIDSs List of Users");

            byte currentUserMax = (byte) (data[2] & 0xFF);
            byte currentUserID = (byte) (data[3] & 0xFF);
            byte userUuid = (byte) (data[11] & 0xFF);
            String name = new String(data, 12, 3);
            int year = (byte) (data[15] & 0xFF);

            final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

            // Check if we found the currently selected user
            if (selectedUser.getUserName().toLowerCase().startsWith(name.toLowerCase()) &&
                    selectedUser.getBirthday().getYear() == year) {
                // Found user
                currentScaleUserId = userUuid;
            }

            // Remember this uuid from the scale
            if (seenUsers.add((int) userUuid)) {
                if (currentScaleUserId == -1 && seenUsers.size() == countRegisteredScaleUsers) {
                    // We have seen all users: user is unknown
                    currentScaleUserId = 0;
                }
                Log.d(TAG, "Send ack gotUser");
                writeBytes(new byte[]{
                        (byte) startByte, (byte) 0xf1, (byte) 0x34, currentUserMax,
                        currentUserID
                });
            }

            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0xF0 && (data[2] & 0xFF) == 0x36) {
            Log.d(TAG, "Ack Get User Info Initials");
            String name = new String(data, 4, 3);
            byte year = (byte) (data[7] & 0xFF);
            byte month = (byte) (data[8] & 0xFF);
            byte day = (byte) (data[9] & 0xFF);

            int height = (data[10] & 0xFF);
            boolean male = (data[11] & 0xF0) != 0;
            byte activity = (byte) (data[11] & 0x0F);

            Log.d(TAG, "Name " + name + " YY-MM-DD: " + year + " " + month + " " + day +
                    "Height: " + height + " Sex:" + (male ? "M" : "F") + "activity: " + activity);

            // Get scale status for user
            writeBytes(new byte[]{
                    (byte) startByte, (byte) 0x4f, (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) 0x0,
                    (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) currentScaleUserId
            });

            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0xf0 && (data[2] & 0xFF) == 0x4F) {
            Log.d(TAG, "Ack Get scale status");

            int unknown = data[3];
            int batteryLevel = (data[4] & 0xFF);
            float weightThreshold = (data[5] & 0xFF) / 10f;
            float bodyFatThreshold = (data[6] & 0xFF) / 10f;
            int unit = data[7]; // 1 kg, 2 lb (pounds), 3 st stone
            boolean userExists = (data[8] == 0);
            boolean userReferWeightExists = (data[9] == 0);
            boolean userMeasurementExist = (data[10] == 0);
            int scaleVersion = data[11];

            Log.d(TAG, "BatteryLevel:" + batteryLevel + " weightThreshold: " + weightThreshold +
                    " BodyFatThresh: " + bodyFatThreshold + " Unit: " + unit + " userExists: " + userExists +
                    " UserReference Weight Exists: " + userReferWeightExists + " UserMeasurementExists: " + userMeasurementExist +
                    " scaleVersion: " + scaleVersion);
            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0xf0 && data[2] == 0x31) {
            Log.d(TAG, "Acknowledge creation of user");

            // Indicate user to step on scale
            sendMessage(R.string.info_step_on_scale, 0);

            // Request basement measurement
            writeBytes(new byte[]{
                    (byte) startByte, 0x40, 0, 0, 0, 0, 0, 0, 0,
                    (byte) (seenUsers.size() > 0 ? Collections.max(seenUsers) + 1 : 101)
            });

            return;
        }


        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0xf0 && (data[2] & 0xFF) == 0x41) {
            Log.d(TAG, "Will start to receive measurements User Specific");

            byte nr_measurements = data[3];

            Log.d(TAG, "New measurements: " + nr_measurements / 2);
            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0x42) {
            Log.d(TAG, "Specific measurement User specific");

            // Measurements are split into two parts

            int max_items = data[2] & 0xFF;
            int current_item = data[3] & 0xFF;

            // Received even part
            if (current_item % 2 == 1) {
                receivedScaleData = new ByteArrayOutputStream();
            }

            try {
                receivedScaleData.write(Arrays.copyOfRange(data, 4, data.length));
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Send acknowledgement
            writeBytes(new byte[]{
                    (byte) startByte, (byte) 0xf1, (byte) 0x42, (byte) (data[2] & 0xFF),
                    (byte) (data[3] & 0xFF)
            });

            if (current_item % 2 == 0) {
                try {
                    ScaleMeasurement parsedData = parseScaleData(receivedScaleData.toByteArray());
                    addScaleData(parsedData);
                } catch (ParseException e) {
                    Log.d(TAG, "Could not parse byte array: " + byteInHex(receivedScaleData.toByteArray()));
                    e.printStackTrace();
                }
            }

            if (current_item == max_items) {
                // finish and delete
                deleteScaleData();
            }
            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0x58) {
            Log.d(TAG, "Active measurement");
            float weight = getKiloGram(data, 3);
            if ((data[2] & 0xFF) != 0x00) {
                // temporary value;
                sendMessage(R.string.info_measuring, weight);
                return;
            }

            Log.i(TAG, "Got weight: " + weight);

            writeBytes(new byte[]{
                    (byte) startByte, (byte) 0xf1, (byte) (data[1] & 0xFF),
                    (byte) (data[2] & 0xFF), (byte) (data[3] & 0xFF),
            });

            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0x59) {
            // Get stable measurement results
            Log.d(TAG, "Get measurement data " + ((int) data[3]));

            int max_items = (data[2] & 0xFF);
            int current_item = (data[3] & 0xFF);

            // Received first part
            if (current_item == 1) {
                receivedScaleData = new ByteArrayOutputStream();
            } else {
                try {
                    receivedScaleData.write(Arrays.copyOfRange(data, 4, data.length));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            // Send ack that we got the data
            writeBytes(new byte[]{
                    (byte) startByte, (byte) 0xf1,
                    (byte) (data[1] & 0xFF), (byte) (data[2] & 0xFF),
                    (byte) (data[3] & 0xFF),
            });

            if (current_item == max_items) {
                // received all parts
                try {
                    ScaleMeasurement parsedData = parseScaleData(receivedScaleData.toByteArray());
                    addScaleData(parsedData);
                    // Delete data
                    deleteScaleData();
                } catch (ParseException e) {
                    Log.d(TAG, "Parse Exception " + byteInHex(receivedScaleData.toByteArray()));
                }
            }

            return;
        }

        if ((data[0] & 0xFF) == startByte && (data[1] & 0xFF) == 0xf0 && (data[2] & 0xFF) == 0x43) {
            Log.d(TAG, "Acknowledge: Data deleted.");
            return;
        }

        Log.d(TAG, "DataChanged - not handled: " + byteInHex(data));
    }

    private void deleteScaleData() {
        writeBytes(new byte[]{
                (byte) startByte, (byte) 0x43, (byte) 0x0, (byte) 0x0, (byte) 0x0,
                (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) 0x0,
                (byte) currentScaleUserId
        });
    }

    private float getKiloGram(byte[] data, int offset) {
        // Unit is 50 g
        return Converters.fromUnsignedInt16Be(data, offset) * 50.0f / 1000.0f;
    }

    private float getPercent(byte[] data, int offset) {
        // Unit is 0.1 %
        return Converters.fromUnsignedInt16Be(data, offset) / 10.0f;
    }

    private ScaleMeasurement parseScaleData(byte[] data) throws ParseException {
        if (data.length != 11 + 11) {
            throw new ParseException("Parse scala data: unexpected length", 0);
        }

        long timestamp = Converters.fromUnsignedInt32Be(data, 0) * 1000;
        float weight = getKiloGram(data, 4);
        int impedance = Converters.fromUnsignedInt16Be(data, 6);
        float fat = getPercent(data, 8);
        float water = getPercent(data, 10);
        float muscle = getPercent(data, 12);
        float bone = getKiloGram(data, 14);
        int bmr = Converters.fromUnsignedInt16Be(data, 16);
        int amr = Converters.fromUnsignedInt16Be(data, 18);
        float bmi = Converters.fromUnsignedInt16Be(data, 20) / 10.0f;

        ScaleMeasurement receivedMeasurement = new ScaleMeasurement();
        receivedMeasurement.setDateTime(new Date(timestamp));
        receivedMeasurement.setWeight(weight);
        receivedMeasurement.setFat(fat);
        receivedMeasurement.setWater(water);
        receivedMeasurement.setMuscle(muscle);
        receivedMeasurement.setBone(bone);

        Log.i(TAG, "Measurement: " + receivedMeasurement.getDateTime().toString() +
                " Impedance: " + impedance + " Weight:" + weight +
                " Fat: " + fat + " Water: " + water + " Muscle: " + muscle +
                " Bone: " + bone + " BMR: " + bmr + " AMR: " + amr + " BMI: " + bmi);

        return receivedMeasurement;
    }

    private void updateDateTimeBeurer() {
        // Update date/time of the scale
        long unixTime = System.currentTimeMillis() / 1000L;
        byte[] unixTimeBytes = Converters.toUnsignedInt32Be(unixTime);
        Log.d(TAG, "Write new Date/Time:" + unixTime + " " + byteInHex(unixTimeBytes));

        writeBytes(new byte[]{(byte) getAlternativeStartByte(9),
                unixTimeBytes[0], unixTimeBytes[1], unixTimeBytes[2], unixTimeBytes[3]});
    }

    private void setUnitCommand() {
        byte[] command = new byte[] {(byte) startByte, 0x4d, 0x00};
        final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

        switch (selectedUser.getScaleUnit()) {
            case KG:
                command[2] = (byte) 0x01;
                break;
            case LB:
                command[2] = (byte) 0x02;
                break;
            case ST:
                command[3] = (byte) 0x04;
                break;
        }
        Log.d(TAG, "Setting unit " + selectedUser.getScaleUnit().toString());
        writeBytes(command);
    }

    private void writeBytes(byte[] data) {
        writeBytes(CUSTOM_SERVICE_1, CUSTOM_CHARACTERISTIC_WEIGHT, data);
    }
}
