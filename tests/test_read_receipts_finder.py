from argparse import Namespace

from artifactory_generator.read_receipts import ReadReceiptsFinder

# The raw accessor, which is what a chat is keyed on -- not toString(), which
# returns the obfuscated form. Pinned by the two things it reads, so the decoy
# below (same signature, same class) must not match.
JID = ('.class public abstract Lcom/whatsapp/infra/core/jid/Jid;\n'
       '.super Ljava/lang/Object;\n\n'
       '.method public getRawString()Ljava/lang/String;\n'
       '    .locals 3\n'
       '    iget-object v0, p0, Lcom/whatsapp/infra/core/jid/Jid;->user:Ljava/lang/String;\n'
       '    invoke-virtual {p0}, Lcom/whatsapp/infra/core/jid/Jid;->getServer()Ljava/lang/String;\n'
       '    move-result-object v1\n'
       '    return-object v0\n'
       '.end method\n'
       '.method public toString()Ljava/lang/String;\n'
       '    .locals 1\n'
       '    invoke-virtual {p0}, Lcom/whatsapp/infra/core/jid/Jid;->getObfuscatedString()Ljava/lang/String;\n'
       '    move-result-object v0\n'
       '    return-object v0\n'
       '.end method\n')


def utils(const='const-string'):
    """ReadReceiptUtils: the receipt-type method, its two helpers, and the gate.

    A06 is the decoy the real class carries -- a public (Jid)Z that delegates
    to the gate instead of calling the helpers itself.
    """
    return ('.class public LX/19A;\n'
            '.super Ljava/lang/Object;\n\n'
            '.method public A05(LX/0Cw;Z)Ljava/lang/String;\n'
            '    .locals 2\n'
            '    %s v1, "read-self"\n'
            '    invoke-direct {p0, p1}, LX/19A;->A00(LX/0Cw;)Z\n'
            '    invoke-direct {p0, p1}, LX/19A;->A01(LX/0Cw;)Z\n'
            '    %s v0, "read"\n'
            '    return-object v0\n'
            '.end method\n'
            '.method private A00(LX/0Cw;)Z\n'
            '    .locals 1\n'
            '    const/4 v0, 0x1\n'
            '    return v0\n'
            '.end method\n'
            '.method private A01(LX/0Cw;)Z\n'
            '    .locals 1\n'
            '    const/4 v0, 0x1\n'
            '    return v0\n'
            '.end method\n'
            '.method public A07(LX/0Cw;)Z\n'
            '    .locals 1\n'
            '    const-string v0, "ReadReceiptUtils/gate"\n'
            '    invoke-direct {p0, p1}, LX/19A;->A00(LX/0Cw;)Z\n'
            '    invoke-direct {p0, p1}, LX/19A;->A01(LX/0Cw;)Z\n'
            '    const/4 v0, 0x1\n'
            '    return v0\n'
            '.end method\n'
            '.method public A06(LX/0Cw;)Z\n'
            '    .locals 1\n'
            '    invoke-virtual {p0, p1}, LX/19A;->A07(LX/0Cw;)Z\n'
            '    move-result v0\n'
            '    return v0\n'
            '.end method\n' % (const, const))


UTILS = utils()


def job_method(name, const='const-string', played=True, store=True):
    return ('.method public %s()V\n'
            '    .locals 9\n'
            '%s%s'
            '    return-void\n'
            '.end method\n'
            % (name,
               '    %s v8, "played"\n' % const if played else '',
               '    %s v0, "PlayedSelfReceiptStore/insertPlayedSelfReceipt/toJid = "\n' % const if store else ''))


def job(*methods):
    """SendPlayedReceiptJobV2, whose package name obfuscation does not touch."""
    return ('.class public Lcom/whatsapp/messaging/receipts/jobqueue/job/SendPlayedReceiptJobV2;\n'
            '.super Lorg/whispersystems/jobqueue/Job;\n\n'
            + '.method public A0E()V\n    .locals 0\n    return-void\n.end method\n'
            + ''.join(methods))


JOB = job(job_method('A0G'))

# Same shape, unrelated class: the anchors are what keeps it out.
OTHER = ('.class public LX/9zz;\n.super Ljava/lang/Object;\n\n'
         + job_method('A0G'))


def finder():
    return ReadReceiptsFinder(Namespace(apk_path='x.apk', temp_path='./temp'))


def feed(found, *classes):
    artifacts = {}
    for class_data in classes:
        if found.class_filter(class_data):
            found.extract_artifacts(artifacts, class_data)
    return artifacts


def test_resolves_every_artifact():
    found = finder()
    artifacts = feed(found, JID, UTILS, JOB, OTHER)
    assert found.is_found is True
    assert artifacts['JID_CLASS_NAME'] == 'com.whatsapp.infra.core.jid.Jid'
    assert artifacts['JID_RAW_STRING_METHOD_NAME'] == 'getRawString'
    assert artifacts['READ_RECEIPT_UTILS_CLASS_NAME'] == 'X.19A'
    assert artifacts['RECEIPT_TYPE_METHOD_NAME'] == 'A05'
    assert artifacts['RECEIPT_TYPE_METHOD_SIG'] == '(LX/0Cw;Z)Ljava/lang/String;'
    assert artifacts['PLAYED_RECEIPT_GATE_METHOD_NAME'] == 'A07'
    assert artifacts['PLAYED_RECEIPT_GATE_METHOD_SIG'] == '(LX/0Cw;)Z'
    assert artifacts['PLAYED_RECEIPT_JOB_CLASS_NAME'] == \
        'com.whatsapp.messaging.receipts.jobqueue.job.SendPlayedReceiptJobV2'
    assert artifacts['PLAYED_RECEIPT_JOB_METHOD_NAME'] == 'A0G'
    assert artifacts['PLAYED_RECEIPT_JOB_METHOD_SIG'] == '()V'


def test_order_does_not_matter():
    """Files arrive in filesystem order, so the job may be scanned first."""
    found = finder()
    artifacts = feed(found, JOB, OTHER, UTILS, JID)
    assert found.is_found is True
    assert artifacts['PLAYED_RECEIPT_JOB_METHOD_NAME'] == 'A0G'
    assert artifacts['PLAYED_RECEIPT_GATE_METHOD_NAME'] == 'A07'


def test_reads_the_jumbo_string_variant():
    """Large string indices get const-string/jumbo, which some builds use."""
    found = finder()
    artifacts = feed(found, JID, utils('const-string/jumbo'),
                     job(job_method('A0G', const='const-string/jumbo')))
    assert found.is_found is True
    assert artifacts['RECEIPT_TYPE_METHOD_NAME'] == 'A05'
    assert artifacts['PLAYED_RECEIPT_JOB_METHOD_NAME'] == 'A0G'


def test_does_not_fire_without_the_played_job():
    """The gate alone is not enough: unscoped, it would clamp incoming receipts."""
    found = finder()
    feed(found, JID, UTILS)
    assert found.is_found is False


def test_does_not_fire_on_an_ambiguous_job():
    """Two candidate methods means the run method is not identified -- do not guess."""
    found = finder()
    artifacts = feed(found, JID, UTILS, job(job_method('A0F'), job_method('A0G')))
    assert found.is_found is False
    assert 'PLAYED_RECEIPT_JOB_METHOD_NAME' not in artifacts


def test_ignores_a_job_method_missing_an_anchor():
    """Both the wire literal and the log prefix have to be there."""
    found = finder()
    artifacts = feed(found, JID, UTILS, job(job_method('A0G', store=False)))
    assert found.is_found is False
    assert 'PLAYED_RECEIPT_JOB_METHOD_NAME' not in artifacts
