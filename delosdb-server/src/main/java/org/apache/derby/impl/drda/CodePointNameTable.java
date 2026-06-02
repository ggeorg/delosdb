/*

   Derby - Class org.apache.derby.impl.drda.CodePointNameTable

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
package org.apache.derby.impl.drda;

import java.util.HashMap;
import java.util.Map;

/**
  This class has a map of CodePoint values.  It is used by the tracing
  code and by the protocol testing code
  It is arranged in alphabetical order.
*/
class CodePointNameTable
{
  private final Map<Integer, String> names = new HashMap<Integer, String>();

  CodePointNameTable ()
  {
    names.put(CodePoint.ABNUOWRM, "ABNUOWRM");
    names.put(CodePoint.ACCRDB, "ACCRDB");
    names.put(CodePoint.ACCRDBRM, "ACCRDBRM");
    names.put(CodePoint.ACCSEC, "ACCSEC");
    names.put(CodePoint.ACCSECRD, "ACCSECRD");
    names.put(CodePoint.AGENT, "AGENT");
    names.put(CodePoint.AGNPRMRM, "AGNPRMRM");
    names.put(CodePoint.BGNBND, "BGNBND");
    names.put(CodePoint.BGNBNDRM, "BGNBNDRM");
    names.put(CodePoint.BNDSQLSTT, "BNDSQLSTT");
    names.put(CodePoint.CCSIDSBC, "CCSIDSBC");
    names.put(CodePoint.CCSIDMBC, "CCSIDMBC");
    names.put(CodePoint.CCSIDDBC, "CCSIDDBC");
    names.put(CodePoint.CLSQRY, "CLSQRY");
    names.put(CodePoint.CMDATHRM, "CMDATHRM");
    names.put(CodePoint.CMDCHKRM, "CMDCHKRM");
    names.put(CodePoint.CMDCMPRM, "CMDCMPRM");
    names.put(CodePoint.CMDNSPRM, "CMDNSPRM");
    names.put(CodePoint.CMMRQSRM, "CMMRQSRM");
    names.put(CodePoint.CMDVLTRM, "CMDVLTRM");
    names.put(CodePoint.CNTQRY, "CNTQRY");
    names.put(CodePoint.CRRTKN, "CRRTKN");
    names.put(CodePoint.DRPPKG, "DRPPKG");
    names.put(CodePoint.DSCRDBTBL, "DSCRDBTBL");
    names.put(CodePoint.DSCINVRM, "DSCINVRM");
    names.put(CodePoint.DSCSQLSTT, "DSCSQLSTT");
    names.put(CodePoint.DTAMCHRM, "DTAMCHRM");
    names.put(CodePoint.ENDBND, "ENDBND");
    names.put(CodePoint.ENDQRYRM, "ENDQRYRM");
    names.put(CodePoint.ENDUOWRM, "ENDUOWRM");
    names.put(CodePoint.EXCSAT, "EXCSAT");
    names.put(CodePoint.EXCSATRD, "EXCSATRD");
    names.put(CodePoint.EXCSQLIMM, "EXCSQLIMM");
    names.put(CodePoint.EXCSQLSET, "EXCSQLSET");
    names.put(CodePoint.EXCSQLSTT, "EXCSQLSTT");
    names.put(CodePoint.EXTNAM, "EXTNAM");
    names.put(CodePoint.FRCFIXROW, "FRCFIXROW");
    names.put(CodePoint.MAXBLKEXT, "MAXBLKEXT");
    names.put(CodePoint.MAXRSLCNT, "MAXRSLCNT");
    names.put(CodePoint.MGRDEPRM, "MGRDEPRM");
    names.put(CodePoint.MGRLVLLS, "MGRLVLLS");
    names.put(CodePoint.MGRLVLRM, "MGRLVLRM");
    names.put(CodePoint.MONITOR, "MONITOR");
    names.put(CodePoint.NBRROW, "NBRROW");
    names.put(CodePoint.OBJNSPRM, "OBJNSPRM");
    names.put(CodePoint.OPNQFLRM, "OPNQFLRM");
    names.put(CodePoint.OPNQRY, "OPNQRY");
    names.put(CodePoint.OPNQRYRM, "OPNQRYRM");
    names.put(CodePoint.OUTEXP, "OUTEXP");
    names.put(CodePoint.OUTOVR, "OUTOVR");
    names.put(CodePoint.OUTOVROPT, "OUTOVROPT");
    names.put(CodePoint.PASSWORD, "PASSWORD");
    names.put(CodePoint.PKGID, "PKGID");
    names.put(CodePoint.PKGBNARM, "PKGBNARM");
    names.put(CodePoint.PKGBPARM, "PKGBPARM");
    names.put(CodePoint.PKGNAMCSN, "PKGNAMCSN");
    names.put(CodePoint.PKGNAMCT, "PKGNAMCT");
    names.put(CodePoint.PRCCNVRM, "PRCCNVRM");
    names.put(CodePoint.PRDID, "PRDID");
    names.put(CodePoint.PRDDTA, "PRDDTA");
    names.put(CodePoint.PRMNSPRM, "PRMNSPRM");
    names.put(CodePoint.PRPSQLSTT, "PRPSQLSTT");
    names.put(CodePoint.QRYBLKCTL, "QRYBLKCTL");
    names.put(CodePoint.QRYBLKRST, "QRYBLKRST");
    names.put(CodePoint.QRYBLKSZ, "QRYBLKSZ");
    names.put(CodePoint.QRYCLSIMP, "QRYCLSIMP");
    names.put(CodePoint.QRYCLSRLS, "QRYCLSRLS");
    names.put(CodePoint.QRYDSC, "QRYDSC");
    names.put(CodePoint.QRYDTA, "QRYDTA");
    names.put(CodePoint.QRYINSID, "QRYINSID");
    names.put(CodePoint.QRYNOPRM, "QRYNOPRM");
    names.put(CodePoint.QRYPOPRM, "QRYPOPRM");
    names.put(CodePoint.QRYRELSCR, "QRYRELSCR");
    names.put(CodePoint.QRYRFRTBL, "QRYRFRTBL");
    names.put(CodePoint.QRYROWNBR, "QRYROWNBR");
    names.put(CodePoint.QRYROWSNS, "QRYROWSNS");
    names.put(CodePoint.QRYRTNDTA, "QRYRTNDTA");
    names.put(CodePoint.QRYSCRORN, "QRYSCRORN");
    names.put(CodePoint.QRYROWSET, "QRYROWSET");
    names.put(CodePoint.RDBAFLRM, "RDBAFLRM");
    names.put(CodePoint.RDBACCCL, "RDBACCCL");
    names.put(CodePoint.RDBACCRM, "RDBACCRM");
    names.put(CodePoint.RDBALWUPD, "RDBALWUPD");
    names.put(CodePoint.RDBATHRM, "RDBATHRM");
    names.put(CodePoint.RDBCMM, "RDBCMM");
    names.put(CodePoint.RDBCMTOK, "RDBCMTOK");
    names.put(CodePoint.RDBNACRM, "RDBNACRM");
    names.put(CodePoint.RDBNAM, "RDBNAM");
    names.put(CodePoint.RDBNFNRM, "RDBNFNRM");
    names.put(CodePoint.RDBRLLBCK, "RDBRLLBCK");
    names.put(CodePoint.RDBUPDRM, "RDBUPDRM");
    names.put(CodePoint.REBIND, "REBIND");
    names.put(CodePoint.RSCLMTRM, "RSCLMTRM");
    names.put(CodePoint.RSLSETRM, "RSLSETRM");
    names.put(CodePoint.RTNEXTDTA, "RTNEXTDTA");
    names.put(CodePoint.RTNSQLDA, "RTNSQLDA");
    names.put(CodePoint.SECCHK, "SECCHK");
    names.put(CodePoint.SECCHKCD, "SECCHKCD");
    names.put(CodePoint.SECCHKRM, "SECCHKRM");
    names.put(CodePoint.SECMEC, "SECMEC");
    names.put(CodePoint.SECMGRNM, "SECMGRNM");
    names.put(CodePoint.SECTKN, "SECTKN");
    names.put(CodePoint.SPVNAM, "SPVNAM");
    names.put(CodePoint.SQLAM, "SQLAM");
    names.put(CodePoint.SQLATTR, "SQLATTR");
    names.put(CodePoint.SQLCARD, "SQLCARD");
    names.put(CodePoint.SQLERRRM, "SQLERRRM");
    names.put(CodePoint.SQLDARD, "SQLDARD");
    names.put(CodePoint.SQLDTA, "SQLDTA");
    names.put(CodePoint.SQLDTARD, "SQLDTARD");
    names.put(CodePoint.SQLSTT, "SQLSTT");
    names.put(CodePoint.SQLSTTVRB, "SQLSTTVRB");
    names.put(CodePoint.SRVCLSNM, "SRVCLSNM");
    names.put(CodePoint.SRVRLSLV, "SRVRLSLV");
    names.put(CodePoint.SRVNAM, "SRVNAM");
    names.put(CodePoint.SVRCOD, "SVRCOD");
    names.put(CodePoint.SYNCCTL, "SYNCCTL");
    names.put(CodePoint.SYNCLOG, "SYNCLOG");
    names.put(CodePoint.SYNCRSY, "SYNCRSY");
    names.put(CodePoint.SYNTAXRM, "SYNTAXRM");
    names.put(CodePoint.TRGNSPRM, "TRGNSPRM");
    names.put(CodePoint.TYPDEFNAM, "TYPDEFNAM");
    names.put(CodePoint.TYPDEFOVR, "TYPDEFOVR");
    names.put(CodePoint.TYPSQLDA, "TYPSQLDA");
    names.put(CodePoint.UOWDSP, "UOWDSP");
    names.put(CodePoint.USRID, "USRID");
    names.put(CodePoint.VALNSPRM, "VALNSPRM");
    names.put(CodePoint.PBSD, "PBSD");
    names.put(CodePoint.PBSD_ISO, "PBSD_ISO");
    names.put(CodePoint.PBSD_SCHEMA, "PBSD_SCHEMA");
    names.put(CodePoint.UNICODEMGR, "UNICODEMGR");
  }

  String lookup (int codePoint)
  {
    return names.get(codePoint);
  }

  Integer codePointForName(String codePointName)
  {
    for (Map.Entry<Integer, String> entry : names.entrySet())
    {
      if (codePointName.equals(entry.getValue()))
      {
        return entry.getKey();
      }
    }
    return null;
  }

}
