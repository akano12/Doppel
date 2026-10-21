/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2019 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

class XApp implements Parcelable {
    String packageName;
    int uid;
    int icon;
    String label;
    boolean persistent;
    boolean system;
    boolean forceStop = true;
    long installed = -1; // First install time
    List<XAssignment> assignments;

    XApp() {
    }

    // The name shown, the package name when the app has no label
    String getLabel() {
        return (TextUtils.isEmpty(this.label) ? this.packageName : this.label);
    }

    List<XAssignment> getAssignments(String group) {
        List<XAssignment> filtered = new ArrayList<>();
        for (XAssignment assignment : assignments)
            if (group.equals(assignment.hook.getGroup()))
                filtered.add(assignment);

        return filtered;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof XApp))
            return false;
        XApp other = (XApp) obj;
        return (this.packageName.equals(other.packageName) && this.uid == other.uid);
    }

    @Override
    public int hashCode() {
        return this.packageName.hashCode();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.packageName);
        dest.writeInt(this.uid);
        dest.writeInt(this.icon);
        dest.writeString(this.label);
        dest.writeByte(this.persistent ? (byte) 1 : (byte) 0);
        dest.writeByte(this.system ? (byte) 1 : (byte) 0);
        dest.writeByte(this.forceStop ? (byte) 1 : (byte) 0);
        dest.writeLong(this.installed);
        dest.writeTypedList(this.assignments);
    }

    protected XApp(Parcel in) {
        this.packageName = in.readString();
        this.uid = in.readInt();
        this.icon = in.readInt();
        this.label = in.readString();
        this.persistent = (in.readByte() != 0);
        this.system = (in.readByte() != 0);
        this.forceStop = (in.readByte() != 0);
        this.installed = in.readLong();
        this.assignments = in.createTypedArrayList(XAssignment.CREATOR);
    }

    public static final Parcelable.Creator<XApp> CREATOR = new Parcelable.Creator<XApp>() {
        @Override
        public XApp createFromParcel(Parcel source) {
            return new XApp(source);
        }

        @Override
        public XApp[] newArray(int size) {
            return new XApp[size];
        }
    };
}
