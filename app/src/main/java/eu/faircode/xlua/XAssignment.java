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

class XAssignment implements Parcelable {
    XHook hook;
    long installed = -1;
    long assigned = -1; // Unknown for hooks assigned by former versions and by updates
    long used = -1;
    boolean restricted = false;
    String exception;

    XAssignment(XHook hook) {
        this.hook = hook;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof XAssignment))
            return false;
        XAssignment other = (XAssignment) obj;
        return this.hook.getId().equals(other.hook.getId());
    }

    @Override
    public int hashCode() {
        return this.hook.getId().hashCode();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(this.hook, flags);
        dest.writeLong(this.installed);
        dest.writeLong(this.assigned);
        dest.writeLong(this.used);
        dest.writeByte(this.restricted ? (byte) 1 : (byte) 0);
        dest.writeString(this.exception);
    }

    protected XAssignment(Parcel in) {
        this.hook = in.readTypedObject(XHook.CREATOR);
        this.installed = in.readLong();
        this.assigned = in.readLong();
        this.used = in.readLong();
        this.restricted = (in.readByte() != 0);
        this.exception = in.readString();
    }

    public static final Parcelable.Creator<XAssignment> CREATOR = new Parcelable.Creator<XAssignment>() {
        @Override
        public XAssignment createFromParcel(Parcel source) {
            return new XAssignment(source);
        }

        @Override
        public XAssignment[] newArray(int size) {
            return new XAssignment[size];
        }
    };
}
