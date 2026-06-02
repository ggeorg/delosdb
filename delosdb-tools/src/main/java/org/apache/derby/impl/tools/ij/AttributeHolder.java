/*

   Derby - Class org.apache.derby.impl.tools.ij.AttributeHolder

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */


package org.apache.derby.impl.tools.ij;

import org.apache.derby.iapi.tools.i18n.LocalizedResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AttributeHolder {

    //This is an inner class.  This class hold the details about each
    //specific attribute which includes what the attribute is and
    //any error found.
    String name;
    String value;
    String token;
    private final List<String> errors = new ArrayList<String>();

    public String getName(){
      return name;
    }
    public void setName(String aString){
      name = aString;
    }
    String getValue(){
      return value;
    }
    public void setValue(String aString){
      value = aString;
    }
    String getToken(){
      return token;
    }
    public void setToken(String aString){
      token = aString;
    }
    public void addError(String aString) {
      //Keep track of error message for later display.
      if (!errors.contains(aString))
        errors.add(aString);
    }
   public void check(List<String> validProps){
      checkName( validProps);
      //checkValue();
      displayErrors();
    }
    void displayErrors(){
      //If no error are found then nothing is displayed.
      //In the first line, show the exact token that was parsed from
      //the URL.
      if (!errors.isEmpty())
        display(LocalizedResource.getMessage("TL_urlLabel1", "[", getToken(), "]"));
      //Show all errors.  More than one error can be found for an attribute.
      for (String aString : errors) {
        displayIndented(aString);
      }
    }
    void checkName(List<String> validProps){
      if( validProps == null)
          return; // valid properties are unknown
      String anAtt = getName();
      try {
        //Check the found name against valid names.
        if (!validProps.contains(anAtt)) {
          //Check for case spelling of the name.
          if (validProps.contains(anAtt.toLowerCase(java.util.Locale.ENGLISH))) {
            errors.add(LocalizedResource.getMessage("TL_incorCase"));
          }
          //Check if this is even a valid attribute name.
          else {
            errors.add(LocalizedResource.getMessage("TL_unknownAtt"));
          }
        }
        else {
          //This Is a valid attribute.
        }
      }
      catch (Exception ex) {
        ex.printStackTrace();
      }
    }
    void checkValue(){
      String anAtt = getName(); 
      String aValue = getValue();
      try {
        //Check all attribute that require a boolean.
        if (URLCheck.getBooleanAttributes().contains(anAtt)) {
          if (!checkBoolean(aValue)) {
            errors.add(LocalizedResource.getMessage("TL_trueFalse"));
          }
        }
      }
      catch (Exception ex) {
        ex.printStackTrace();
      }
    }
	  boolean checkBoolean(String aValue) {
		  if (aValue == null)
			  return false;
		  return aValue.toLowerCase(Locale.ENGLISH).equals("true") || 
			  aValue.toLowerCase(Locale.ENGLISH).equals("false");
	  }
    void display(String aString) {
		LocalizedResource.OutputWriter().println(aString);
    }
    void displayIndented(String aString) {
		LocalizedResource.OutputWriter().println("   " + aString);
    }
  }
